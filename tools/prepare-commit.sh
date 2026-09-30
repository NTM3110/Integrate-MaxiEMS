#!/bin/bash
# Prepare nested Bnd bundles without touching unrelated workspace directories.
set -euo pipefail
cd "$(dirname "$0")/.."

while IFS= read -r -d '' descriptor; do
	bundle="${descriptor%/bnd.bnd}"
	mkdir -p "${bundle}/test"
	touch "${bundle}/test/.gitignore"
	for pattern in /bin/ /bin_test/ /generated/; do
		if ! grep -qxF "${pattern}" "${bundle}/.gitignore" 2>/dev/null; then
			printf '\n%s\n' "${pattern}" >> "${bundle}/.gitignore"
		fi
	done
done < <(find apps platform -name bnd.bnd -type f -print0)

bash ./gradlew build
# Keep curated runtime requirements. Regenerating them from all source bundles
# would activate app-specific services and include intentionally excluded bundles.
bash ./gradlew :io.openems.edge.application:resolve.EdgeApp \
	:io.openems.backend.application:resolve.BackendApp \
	:io.openems.backend.edge.application:resolve.BackendEdgeApp

if [ -d ui ]; then
	(
		cd ui
		npm install
		node_modules/.bin/ng lint --fix
		node_modules/.bin/tsc
		node_modules/.bin/tsc-strict
		node_modules/.bin/ng build -c "openems,openems-backend-prod,prod"
		npm run test -- --no-watch --no-progress --browsers=ChromeHeadlessCI
	)
fi
echo "Finished"
