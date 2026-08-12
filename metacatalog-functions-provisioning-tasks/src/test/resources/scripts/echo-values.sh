#!/usr/bin/env bash
# Test double for ScriptProvisioningTask: prints the operation it was asked to perform ($1,
# provision or unprovision) and the entity JSON values it received on standard input.
echo "[script:$1] $(cat)"
