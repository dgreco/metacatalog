#!/usr/bin/env bash
# Test double for ScriptProvisioningTask: prints the operation it was asked to perform ($1 —
# provision, unprovision, authorize or reject), the grants it was handed in the environment, and the
# entity JSON values it received on standard input.
#
# METACATALOG_ACCESS is deliberately reported as <unset> when absent rather than as an empty array:
# the difference between "this run resolved a policy and nobody was granted anything" and "this run
# resolves no policy at all" is exactly what the task's contract promises, and a script that could
# not tell them apart would be free to break it.
echo "[script:$1] access=${METACATALOG_ACCESS:-<unset>} $(cat)"
