#!/usr/bin/env bash
set -euo pipefail

# Run from anywhere inside the Flow Graph git checkout.
repo_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$repo_root" ]]; then
  echo "Error: not inside a git repository." >&2
  exit 1
fi
cd "$repo_root"

old_paths=(
  "instrumentation/flowgraph-gradle-plugin/src/main/kotlin/dev/flowgraph"
  "instrumentation/flowgraph-runtime/src/main/java/dev/flowgraph"
  "src/main/kotlin/dev/flowgraph"
  "src/test/kotlin/dev/flowgraph"
)

new_paths=(
  "instrumentation/flowgraph-gradle-plugin/src/main/kotlin/com/oskiapps/flowgraph"
  "instrumentation/flowgraph-runtime/src/main/java/com/oskiapps/flowgraph"
  "src/main/kotlin/com/oskiapps/flowgraph"
  "src/test/kotlin/com/oskiapps/flowgraph"
)

echo "Removing old dev.flowgraph namespace..."
# Stage tracked old files as deletions, then also remove any untracked leftovers.
git rm -r --ignore-unmatch -- "${old_paths[@]}" >/dev/null || true
for path in "${old_paths[@]}"; do
  rm -rf -- "$path"
done

# Remove common temporary editor/test leftovers. Git does not track directories,
# so removing these files also makes the old empty folders disappear in the IDE.
find src instrumentation -type f -name '*.tmp' -print -delete 2>/dev/null || true

# Ensure temporary files do not come back.
if ! grep -qxF '*.tmp' .gitignore 2>/dev/null; then
  printf '\n# Temporary editor/test files\n*.tmp\n' >> .gitignore
fi

echo "Staging com.oskiapps.flowgraph namespace..."
for path in "${new_paths[@]}"; do
  if [[ -e "$path" ]]; then
    git add -- "$path"
  else
    echo "Warning: expected new namespace path is missing: $path" >&2
  fi
done

git add -- .gitignore \
  build.gradle.kts gradle.properties \
  src/main/resources/META-INF/plugin.xml \
  instrumentation/flowgraph-gradle-plugin/build.gradle.kts \
  instrumentation/flowgraph-runtime/build.gradle.kts \
  README.md CHANGELOG.md PUBLISHING.md 2>/dev/null || true

echo
if grep -RInE \
  --include='*.kt' --include='*.java' --include='*.kts' --include='*.xml' \
  '(^|[^A-Za-z0-9_])dev\.flowgraph' \
  src instrumentation 2>/dev/null; then
  echo
  echo "Error: active source/config references to dev.flowgraph still remain." >&2
  exit 2
fi

echo "Namespace cleanup complete. Current staged/unstaged changes:"
git status --short

echo
echo "Review the changes, then commit, for example:"
echo '  git commit -m "Migrate Flow Graph namespace to com.oskiapps.flowgraph"'
