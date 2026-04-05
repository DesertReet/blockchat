#!/usr/bin/env bash
set -euo pipefail

: "${GH_TOKEN:?Missing GH_TOKEN}"
: "${MODRINTH_TOKEN:?Missing MODRINTH_TOKEN}"
: "${MODRINTH_PROJECT_ID:?Missing MODRINTH_PROJECT_ID}"
: "${TAG_NAME:?Missing TAG_NAME}"
: "${GITHUB_REPOSITORY:?Missing GITHUB_REPOSITORY}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VERSION="${TAG_NAME#v}"
RELEASE_JAR_DIR="${REPO_ROOT}/modrinth-jars"
UNIVERSAL_JAR_DIR="${REPO_ROOT}/modrinth-universal"
USER_AGENT="${GITHUB_REPOSITORY}/blockchat/${VERSION} (GitHub Actions)"

mkdir -p "${RELEASE_JAR_DIR}" "${UNIVERSAL_JAR_DIR}"

gh release download "${TAG_NAME}" --repo "${GITHUB_REPOSITORY}" \
	--pattern "desertreet-blockchat-*.jar" \
	-D "${RELEASE_JAR_DIR}"

shopt -s nullglob
release_jars=("${RELEASE_JAR_DIR}"/desertreet-blockchat-*.jar)
shopt -u nullglob
if [ ${#release_jars[@]} -eq 0 ]; then
	echo "No BlockChat jars found in release ${TAG_NAME}"
	exit 1
fi

declare -A modrinth_game_versions=()
declare -A modrinth_extra_game_versions=()
declare -A fabric_api_versions=()
while IFS= read -r -d '' properties_file; do
	minecraft_version="$(awk -F= '$1=="minecraft_version"{print $2}' "${properties_file}")"
	archive_version="$(awk -F= '$1=="archive_minecraft_version"{print $2}' "${properties_file}")"
	modrinth_game_version="$(awk -F= '$1=="modrinth_game_version"{print $2}' "${properties_file}")"
	modrinth_extra_game_version_list="$(awk -F= '$1=="modrinth_extra_minecraft_versions"{print $2}' "${properties_file}")"
	fabric_api_version="$(awk -F= '$1=="fabric_api_version"{print $2}' "${properties_file}")"

	if [ -z "${minecraft_version}" ] || [ -z "${fabric_api_version}" ]; then
		echo "Missing minecraft_version or fabric_api_version in ${properties_file}"
		exit 1
	fi

	if [ -z "${archive_version}" ]; then
		archive_version="${minecraft_version}"
	fi
	if [ -z "${modrinth_game_version}" ]; then
		modrinth_game_version="${archive_version}"
	fi

	modrinth_game_versions["${archive_version}"]="${modrinth_game_version}"
	modrinth_extra_game_versions["${archive_version}"]="${modrinth_extra_game_version_list}"
	fabric_api_versions["${archive_version}"]="${fabric_api_version}"
done < <(find "${REPO_ROOT}/client/versions" -name version.properties -print0 | sort -z)

declare -A grouped_jars=()
for jar in "${release_jars[@]}"; do
	base_name="$(basename "${jar}" .jar)"
	if [[ "${base_name}" =~ ^desertreet-blockchat-(.+)-(macos-arm64|macos-amd64|windows)\+([0-9]+\.[0-9]+(\.[0-9]+)?)$ ]]; then
		archive_version="${BASH_REMATCH[3]}"
		grouped_jars["${archive_version}"]+=$'\n'"${jar}"
	fi
done

if [ ${#grouped_jars[@]} -eq 0 ]; then
	echo "Could not group any release jars by Minecraft version"
	exit 1
fi

fetch_modrinth_version_id() {
	local project_slug="$1"
	local version_number="$2"
	local encoded_version
	local response

	encoded_version="$(jq -nr --arg value "${version_number}" '$value|@uri')"
	response="$(curl -fsS \
		-H "Authorization: ${MODRINTH_TOKEN}" \
		-H "User-Agent: ${USER_AGENT}" \
		"https://api.modrinth.com/v2/project/${project_slug}/version/${encoded_version}")"
	jq -er '.id' <<<"${response}"
}

build_universal_jar() {
	local archive_version="$1"
	local modrinth_game_version="$2"
	local output_jar="${UNIVERSAL_JAR_DIR}/desertreet-blockchat-${VERSION}-universal+${modrinth_game_version}.jar"
	local merge_dir
	local expected_platforms=("macos-amd64" "macos-arm64" "windows")
	local jar_count=0

	rm -f "${output_jar}"
	merge_dir="$(mktemp -d)"
	while IFS= read -r jar; do
		[ -n "${jar}" ] || continue
		unzip -qo "${jar}" -d "${merge_dir}"
		jar_count=$((jar_count + 1))
	done <<<"${grouped_jars[${archive_version}]}"

	if [ "${jar_count}" -ne "${#expected_platforms[@]}" ]; then
		echo "Expected ${#expected_platforms[@]} platform jars for Minecraft ${archive_version}, found ${jar_count}"
		rm -rf "${merge_dir}"
		exit 1
	fi

	(
		cd "${merge_dir}"
		zip -qr "${output_jar}" .
	)
	rm -rf "${merge_dir}"
	printf '%s\n' "${output_jar}"
}

create_modrinth_version() {
	local archive_version="$1"
	local modrinth_game_version="$2"
	local extra_game_version_list="$3"
	local fabric_api_version="$4"
	local universal_jar="$5"
	local fabric_api_version_id
	local version_number
	local version_name
	local data_file
	local response_file
	local http_code
	local game_versions_json

	fabric_api_version_id="$(fetch_modrinth_version_id "fabric-api" "${fabric_api_version}")"
	version_number="${VERSION}+${modrinth_game_version}"
	version_name="[${modrinth_game_version}] BlockChat ${version_number}"
	data_file="$(mktemp)"
	response_file="$(mktemp)"
	game_versions_json="$(jq -nc --arg version "${modrinth_game_version}" --arg extras "${extra_game_version_list}" '
		([$version] + (
			if ($extras | length) == 0 then
				[]
			else
				($extras | split(",") | map(gsub("^\\s+|\\s+$"; "")) | map(select(length > 0)))
			end
		)) | unique
	')"

	jq -n \
		--arg project_id "${MODRINTH_PROJECT_ID}" \
		--arg version_number "${version_number}" \
		--arg name "${version_name}" \
		--arg changelog "${RELEASE_BODY:-}" \
		--arg fabric_api_version_id "${fabric_api_version_id}" \
		--argjson game_versions "${game_versions_json}" \
		'{
			name: $name,
			version_number: $version_number,
			changelog: $changelog,
			dependencies: [
				{
					version_id: $fabric_api_version_id,
					dependency_type: "required"
				}
			],
			game_versions: $game_versions,
			version_type: "release",
			loaders: ["fabric"],
			featured: false,
			project_id: $project_id,
			file_parts: ["file0"],
			primary_file: "file0"
		}' > "${data_file}"

	http_code="$(
		curl -sS \
			-X POST "https://api.modrinth.com/v2/version" \
			-H "Authorization: ${MODRINTH_TOKEN}" \
			-H "User-Agent: ${USER_AGENT}" \
			-F "data=@${data_file};type=application/json" \
			-F "file0=@${universal_jar}" \
			-o "${response_file}" \
			-w "%{http_code}"
	)"

	echo "Published Minecraft ${archive_version} to Modrinth as ${version_number}: HTTP ${http_code}"
	cat "${response_file}"
	rm -f "${data_file}" "${response_file}"

	test "${http_code}" = "200"
}

mapfile -t archive_versions < <(printf '%s\n' "${!grouped_jars[@]}" | sort -V)
for archive_version in "${archive_versions[@]}"; do
	modrinth_game_version="${modrinth_game_versions[${archive_version}]:-}"
	extra_game_version_list="${modrinth_extra_game_versions[${archive_version}]:-}"
	fabric_api_version="${fabric_api_versions[${archive_version}]:-}"

	if [ -z "${modrinth_game_version}" ] || [ -z "${fabric_api_version}" ]; then
		echo "Missing Modrinth publish metadata for Minecraft ${archive_version}"
		exit 1
	fi

	universal_jar="$(build_universal_jar "${archive_version}" "${modrinth_game_version}")"
	create_modrinth_version "${archive_version}" "${modrinth_game_version}" "${extra_game_version_list}" "${fabric_api_version}" "${universal_jar}"
done
