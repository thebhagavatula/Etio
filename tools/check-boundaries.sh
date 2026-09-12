#!/usr/bin/env bash
#
# Pre-merge gate. Run it on your feature branch before you open the PR.
#
#   bash tools/check-boundaries.sh            # compares against main
#   bash tools/check-boundaries.sh develop    # or another base
#
# Works in Git Bash on Windows, which ships with Git for Windows.
#
# It answers three questions:
#   1. Did you touch a frozen file?            (needs all three owners)
#   2. Did you touch another slice's files?    (guaranteed merge conflict)
#   3. Is USE_FAKE_LLM still true?             (ships a demo with no model)

set -uo pipefail

BASE="${1:-main}"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"

RED=$'\033[31m'; YEL=$'\033[33m'; GRN=$'\033[32m'; DIM=$'\033[2m'; OFF=$'\033[0m'

FROZEN=(
  "app/src/main/java/com/etio/ot/di/ServiceLocator.kt"
  "app/src/main/java/com/etio/ot/ui/Routes.kt"
  "app/src/main/java/com/etio/ot/ui/EtioApp.kt"
  "app/src/main/java/com/etio/ot/EtioApplication.kt"
  "app/src/main/java/com/etio/ot/MainActivity.kt"
  "app/src/main/java/com/etio/ot/core/"
  "app/src/main/java/com/etio/ot/data/model/Enums.kt"
  "app/src/main/java/com/etio/ot/data/local/entity/Entities.kt"
  "app/src/main/AndroidManifest.xml"
  ".gitattributes"
  ".github/CODEOWNERS"
  "tools/check-boundaries.sh"
)

SPINE=(
  "app/src/main/java/com/etio/ot/data/local/"
  "app/src/main/java/com/etio/ot/data/config/"
  "app/src/main/java/com/etio/ot/data/repository/CaseRepository.kt"
  "app/src/main/java/com/etio/ot/domain/timing/"
  "app/src/main/java/com/etio/ot/ui/caselist/"
  "app/src/main/java/com/etio/ot/ui/events/"
  "app/src/main/java/com/etio/ot/ui/theme/"
  "app/src/main/java/com/etio/ot/di/CoreModule.kt"
  "app/src/main/assets/config/seed_cases.json"
  "app/src/main/res/"
  "app/src/test/java/com/etio/ot/domain/TimerEngineTest.kt"
  "app/build.gradle.kts"
  "build.gradle.kts"
  "settings.gradle.kts"
  "gradle.properties"
  "gradle/"
)

AI=(
  "app/src/main/java/com/etio/ot/ai/"
  "app/src/main/java/com/etio/ot/data/repository/DelayRepository.kt"
  "app/src/main/java/com/etio/ot/ui/delay/"
  "app/src/main/java/com/etio/ot/ui/messages/"
  "app/src/main/java/com/etio/ot/di/AiModule.kt"
  "app/src/main/assets/config/prompts.json"
  "app/src/main/assets/config/taxonomy.json"
  "app/src/test/java/com/etio/ot/domain/DelayJsonValidatorTest.kt"
  "app/src/test/java/com/etio/ot/ai/"
)

SAFETY=(
  "app/src/main/java/com/etio/ot/domain/checklist/"
  "app/src/main/java/com/etio/ot/domain/report/"
  "app/src/main/java/com/etio/ot/data/repository/ChecklistRepository.kt"
  "app/src/main/java/com/etio/ot/ui/checklist/"
  "app/src/main/java/com/etio/ot/ui/report/"
  "app/src/main/java/com/etio/ot/di/SafetyModule.kt"
  "app/src/main/assets/config/checklist.json"
  "app/src/test/java/com/etio/ot/domain/ChecklistStateMachineTest.kt"
)

# Docs anyone may edit.
SHARED=( "README.md" "BRANCHES.md" "docs/" ".gitignore" )

case "$BRANCH" in
  feat/spine)  MINE=("${SPINE[@]}");  THEIRS_A=("${AI[@]}");     THEIRS_B=("${SAFETY[@]}"); LANE="spine" ;;
  feat/ai)     MINE=("${AI[@]}");     THEIRS_A=("${SPINE[@]}");  THEIRS_B=("${SAFETY[@]}"); LANE="ai" ;;
  feat/safety) MINE=("${SAFETY[@]}"); THEIRS_A=("${SPINE[@]}");  THEIRS_B=("${AI[@]}");     LANE="safety" ;;
  *)
    echo "${YEL}Branch '$BRANCH' is not one of feat/spine, feat/ai, feat/safety.${OFF}"
    echo "Nothing to check. If this is an integration branch, that's expected."
    exit 0 ;;
esac

matches() {  # matches <path> <prefix...>
  local path="$1"; shift
  local p
  for p in "$@"; do
    case "$path" in "$p"*) return 0 ;; esac
  done
  return 1
}

MERGE_BASE="$(git merge-base "$BASE" HEAD 2>/dev/null)" || {
  echo "${RED}Cannot find a merge base with '$BASE'. Fetch it first.${OFF}"; exit 2; }

CHANGED="$(git diff --name-only "$MERGE_BASE"...HEAD)"

if [ -z "$CHANGED" ]; then
  echo "${DIM}No changes against $BASE.${OFF}"; exit 0
fi

froze=(); poached=(); ok=(); unclaimed=()
while IFS= read -r f; do
  [ -z "$f" ] && continue
  if   matches "$f" "${FROZEN[@]}";   then froze+=("$f")
  elif matches "$f" "${MINE[@]}";     then ok+=("$f")
  elif matches "$f" "${THEIRS_A[@]}" || matches "$f" "${THEIRS_B[@]}"; then poached+=("$f")
  elif matches "$f" "${SHARED[@]}";   then ok+=("$f")
  else unclaimed+=("$f")
  fi
done <<< "$CHANGED"

echo "Branch ${GRN}$BRANCH${OFF} (lane: $LANE) vs ${BASE}  —  $(echo "$CHANGED" | wc -l | tr -d ' ') file(s) changed"
echo

status=0

if [ ${#poached[@]} -gt 0 ]; then
  echo "${RED}✗ ${#poached[@]} file(s) belong to another slice — this WILL conflict:${OFF}"
  printf '    %s\n' "${poached[@]}"
  echo "  ${DIM}Revert them and ask that slice's owner to make the change instead.${OFF}"
  echo
  status=1
fi

if [ ${#froze[@]} -gt 0 ]; then
  echo "${RED}✗ ${#froze[@]} frozen file(s) changed — needs all three owners to agree:${OFF}"
  printf '    %s\n' "${froze[@]}"
  echo "  ${DIM}Frozen files are the contract between branches. Change them on main, together.${OFF}"
  echo
  status=1
fi

if [ ${#unclaimed[@]} -gt 0 ]; then
  echo "${YEL}? ${#unclaimed[@]} new file(s) in no declared lane:${OFF}"
  printf '    %s\n' "${unclaimed[@]}"
  echo "  ${DIM}Fine if they're yours — add the path to this script's lane list so the next run is clean.${OFF}"
  echo
fi

# The flip that silently ships a demo with no model behind it.
if git show HEAD:app/src/main/java/com/etio/ot/di/AiModule.kt 2>/dev/null \
   | grep -q 'USE_FAKE_LLM = true'; then
  echo "${RED}✗ AiModule.USE_FAKE_LLM is true. Set it back to false before merging.${OFF}"
  echo
  status=1
fi

if [ $status -eq 0 ]; then
  echo "${GRN}✓ ${#ok[@]} file(s), all inside your lane. Safe to merge.${OFF}"
else
  echo "${DIM}Fix the above, then re-run. Merging anyway is how you lose an hour at 02:00.${OFF}"
fi

exit $status
