#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../../.." && pwd)
contract_json="${repo_root}/protocol/cambridge-stream-contract.json"
deployment_json="${CAMBRIDGE_DEPLOYMENT_FILE:-${repo_root}/protocol/cambridge-deployment.local.json}"
if [[ ! -f "${deployment_json}" ]]; then
    deployment_json="${repo_root}/protocol/cambridge-deployment.json"
fi
mode="check"

usage() {
    printf 'Usage: %s [--check] [--apply]\n' "${BASH_SOURCE[0]}"
}

while (($# > 0)); do
    case "$1" in
        --check)
            mode="check"
            ;;
        --apply)
            mode="apply"
            ;;
        --help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 2
            ;;
    esac
    shift
done

[[ -f "${contract_json}" ]] || { printf 'error: missing contract: %s\n' "${contract_json}" >&2; exit 1; }
[[ -f "${deployment_json}" ]] || { printf 'error: missing deployment: %s\n' "${deployment_json}" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { printf 'error: jq is required\n' >&2; exit 1; }
command -v ufw >/dev/null 2>&1 || { printf 'error: ufw is required\n' >&2; exit 1; }

control_port=$(jq -er '.defaults.controlPort' "${contract_json}")
media_port=$((control_port + $(jq -er '.defaults.mediaPortOffset' "${contract_json}")))
computer_interface=$(jq -er '.computer.interface' "${deployment_json}")
source_cidr=$(jq -er '.computer.sourceCidr' "${deployment_json}")
[[ -n "${computer_interface}" && -n "${source_cidr}" ]] || {
    printf 'error: configure protocol/cambridge-deployment.local.json or set CAMBRIDGE_DEPLOYMENT_FILE\n' >&2
    exit 1
}

if ((EUID == 0)); then
    ufw_command=(ufw)
else
    command -v sudo >/dev/null 2>&1 || { printf 'error: sudo is required for UFW access\n' >&2; exit 1; }
    ufw_command=(sudo ufw)
fi

show_added() {
    "${ufw_command[@]}" show added
}

rule_present() {
    local rule="$1"
    show_added | rg -F -- "${rule}" >/dev/null
}

check_rule() {
    local rule="$1"
    if rule_present "${rule}"; then
        printf 'present: ufw %s\n' "${rule}"
    else
        printf 'missing: ufw %s\n' "${rule}"
        return 1
    fi
}

apply_rule() {
    local rule="$1"
    if rule_present "${rule}"; then
        printf 'present: ufw %s\n' "${rule}"
    else
        printf 'adding: ufw %s\n' "${rule}"
        "${ufw_command[@]}" ${rule}
    fi
}

control_rule="allow in on ${computer_interface} from ${source_cidr} to any port ${control_port} proto tcp"
media_rule="allow in on ${computer_interface} from ${source_cidr} to any port ${media_port} proto udp"
rules=("${control_rule}" "${media_rule}")

status=0
for rule in "${rules[@]}"; do
    if [[ "${mode}" == "apply" ]]; then
        apply_rule "${rule}"
    elif ! check_rule "${rule}"; then
        status=1
    fi
done

if [[ "${mode}" == "check" ]]; then
    printf 'UFW cambridge check: %s\n' "$([[ ${status} -eq 0 ]] && printf 'ready' || printf 'incomplete')"
fi
exit "${status}"
