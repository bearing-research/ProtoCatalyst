#!/usr/bin/env bash
# Provision ONE EC2 instance, run the full benchmark (scripts/bench.sh), copy results back,
# and terminate. Designed to be invoked once per architecture (Graviton / Intel / AMD) so the
# report's §11 (UnsafeRow) and §11b (Arrow) microbenchmark ratios can be validated cross-arch.
#
# The local working tree is rsync'd to the instance (no private-repo clone / GitHub auth needed),
# excluding generated dirs. Commit + push first if you want disclosure.txt's git SHA to be clean.
#
# Requires: aws CLI with credentials, ssh, rsync. Auto-creates an EC2 key pair and a security
# group (SSH from this machine's public IP only) if not supplied.
#
# Usage:
#   scripts/ec2-cross-arch.sh \
#     --label graviton --instance-type c7g.4xlarge --cpu-arch arm64 \
#     --region us-east-1 --sf 1 --out results/cross-arch-<ts>/graviton
#
# Flags:
#   --label NAME          short label for this run (graviton|intel|amd|...)        [required]
#   --instance-type T     EC2 instance type (e.g. c7g.4xlarge)                     [required]
#   --cpu-arch ARCH       amd64 | arm64 (selects the Ubuntu 24.04 AMI)             [required]
#   --region R            AWS region                                    [default: $AWS_REGION or us-east-1]
#   --sf SF               scale factor passed to bench.sh                          [default: 1]
#   --out DIR             local dir to copy results into                           [default: results/cross-arch-<ts>/<label>]
#   --key-name NAME       EC2 key pair name (created if absent)        [default: protocatalyst-bench]
#   --key-file PATH       local private key path                       [default: ~/.ssh/<key-name>.pem]
#   --sg-id ID            existing security group id (else one is created)
#   --disk GB             root gp3 size                                            [default: 100]
#   --bench-args "..."    extra args appended to bench.sh (e.g. "--skip-queries")
#   --no-terminate        leave the instance running (debug) — YOU pay until you kill it
#   --dry-run             print the launch plan and exit without spending

set -euo pipefail

LABEL="" ; ITYPE="" ; CPU_ARCH="" ; SF="1" ; OUT=""
REGION="${AWS_REGION:-us-east-1}"
KEY_NAME="protocatalyst-bench" ; KEY_FILE="" ; SG_ID="" ; DISK="100"
BENCH_ARGS="" ; NO_TERMINATE=0 ; DRY_RUN=0
TS="$(date -u +%Y%m%dT%H%M%SZ)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --label) LABEL="$2"; shift 2 ;;
    --instance-type) ITYPE="$2"; shift 2 ;;
    --cpu-arch) CPU_ARCH="$2"; shift 2 ;;
    --region) REGION="$2"; shift 2 ;;
    --sf) SF="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --key-name) KEY_NAME="$2"; shift 2 ;;
    --key-file) KEY_FILE="$2"; shift 2 ;;
    --sg-id) SG_ID="$2"; shift 2 ;;
    --disk) DISK="$2"; shift 2 ;;
    --bench-args) BENCH_ARGS="$2"; shift 2 ;;
    --no-terminate) NO_TERMINATE=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "Unknown flag: $1" >&2; exit 1 ;;
  esac
done

[[ -z "$LABEL" || -z "$ITYPE" || -z "$CPU_ARCH" ]] && { echo "Missing --label/--instance-type/--cpu-arch" >&2; exit 1; }
[[ "$CPU_ARCH" != "amd64" && "$CPU_ARCH" != "arm64" ]] && { echo "--cpu-arch must be amd64 or arm64" >&2; exit 1; }
[[ -z "$KEY_FILE" ]] && KEY_FILE="${HOME}/.ssh/${KEY_NAME}.pem"
[[ -z "$OUT" ]] && OUT="results/cross-arch-${TS}/${LABEL}"

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_ROOT}"
AWS=(aws --region "$REGION")

say() { echo "[$LABEL] $*"; }

# --- AMI (Canonical Ubuntu 24.04 LTS, gp3, per CPU arch) via SSM public parameter ---
AMI="$("${AWS[@]}" ssm get-parameters \
  --names "/aws/service/canonical/ubuntu/server/24.04/stable/current/${CPU_ARCH}/hvm/ebs-gp3/ami-id" \
  --query 'Parameters[0].Value' --output text)"
[[ -z "$AMI" || "$AMI" == "None" ]] && { echo "Could not resolve Ubuntu 24.04 AMI for ${CPU_ARCH} in ${REGION}" >&2; exit 1; }
say "AMI=${AMI} type=${ITYPE} region=${REGION} sf=${SF} out=${OUT}"

if [[ $DRY_RUN -eq 1 ]]; then
  say "DRY RUN — would launch ${ITYPE} (${CPU_ARCH}) from ${AMI}, run 'bench.sh ${SF} ${BENCH_ARGS}', collect to ${OUT}, terminate."
  exit 0
fi

# --- key pair ---
if ! "${AWS[@]}" ec2 describe-key-pairs --key-names "$KEY_NAME" >/dev/null 2>&1; then
  say "creating key pair ${KEY_NAME} -> ${KEY_FILE}"
  mkdir -p "$(dirname "$KEY_FILE")"
  "${AWS[@]}" ec2 create-key-pair --key-name "$KEY_NAME" \
    --query 'KeyMaterial' --output text > "$KEY_FILE"
  chmod 600 "$KEY_FILE"
fi
[[ ! -f "$KEY_FILE" ]] && { echo "Key pair ${KEY_NAME} exists in AWS but ${KEY_FILE} not found locally." >&2; exit 1; }

# --- security group (SSH from this machine only) ---
MYIP="$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')"
if [[ -z "$SG_ID" ]]; then
  VPC_ID="$("${AWS[@]}" ec2 describe-vpcs --filters Name=isDefault,Values=true \
    --query 'Vpcs[0].VpcId' --output text)"
  [[ -z "$VPC_ID" || "$VPC_ID" == "None" ]] && { echo "No default VPC in ${REGION}; pass --sg-id." >&2; exit 1; }
  SG_NAME="protocatalyst-bench-sg"
  SG_ID="$("${AWS[@]}" ec2 describe-security-groups --filters Name=group-name,Values="$SG_NAME" Name=vpc-id,Values="$VPC_ID" \
    --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo None)"
  if [[ -z "$SG_ID" || "$SG_ID" == "None" ]]; then
    say "creating security group ${SG_NAME}"
    SG_ID="$("${AWS[@]}" ec2 create-security-group --group-name "$SG_NAME" \
      --description "ProtoCatalyst benchmark SSH" --vpc-id "$VPC_ID" --query 'GroupId' --output text)"
  fi
  # idempotent ingress for this machine's IP
  "${AWS[@]}" ec2 authorize-security-group-ingress --group-id "$SG_ID" \
    --protocol tcp --port 22 --cidr "${MYIP}/32" >/dev/null 2>&1 || true
fi
say "security group ${SG_ID}, ssh allowed from ${MYIP}/32"

# --- launch ---
INSTANCE_ID="$("${AWS[@]}" ec2 run-instances \
  --image-id "$AMI" --instance-type "$ITYPE" --key-name "$KEY_NAME" \
  --security-group-ids "$SG_ID" \
  --instance-initiated-shutdown-behavior terminate \
  --block-device-mappings "[{\"DeviceName\":\"/dev/sda1\",\"Ebs\":{\"VolumeSize\":${DISK},\"VolumeType\":\"gp3\",\"DeleteOnTermination\":true}}]" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=protocatalyst-bench-${LABEL}},{Key=project,Value=protocatalyst}]" \
  --query 'Instances[0].InstanceId' --output text)"
say "launched ${INSTANCE_ID}"

cleanup() {
  if [[ $NO_TERMINATE -eq 1 ]]; then
    say "--no-terminate set; instance ${INSTANCE_ID} LEFT RUNNING (terminate it yourself)."
    return
  fi
  say "terminating ${INSTANCE_ID}"
  "${AWS[@]}" ec2 terminate-instances --instance-ids "$INSTANCE_ID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

say "waiting for running + status-ok (this takes a couple of minutes)..."
"${AWS[@]}" ec2 wait instance-running --instance-ids "$INSTANCE_ID"
"${AWS[@]}" ec2 wait instance-status-ok --instance-ids "$INSTANCE_ID"

PUBIP="$("${AWS[@]}" ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"
say "public ip ${PUBIP}"

SSH=(ssh -i "$KEY_FILE" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
     -o ConnectTimeout=15 -o ServerAliveInterval=30 ubuntu@"$PUBIP")

# wait for sshd
say "waiting for sshd..."
for i in $(seq 1 40); do
  if "${SSH[@]}" true 2>/dev/null; then break; fi
  sleep 10
  [[ $i -eq 40 ]] && { echo "[$LABEL] sshd never came up" >&2; exit 1; }
done

# --- dead-man's switch: self-terminate after 4h even if this orchestrator dies ---
# (shutdown-behavior=terminate above turns the OS shutdown into an instance termination.)
say "arming 4h self-termination safety net"
"${SSH[@]}" "sudo shutdown -h +240" >/dev/null 2>&1 || true

# --- provision toolchain ---
say "installing JDK 21 + build tools + sbt (coursier)..."
CS_ARCH="x86_64"; [[ "$CPU_ARCH" == "arm64" ]] && CS_ARCH="aarch64"
"${SSH[@]}" bash -se <<EOF
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
sudo apt-get update -qq
sudo apt-get install -y -qq openjdk-21-jdk git make gcc unzip rsync curl >/dev/null
curl -fsLo cs "https://github.com/coursier/launchers/raw/master/cs-${CS_ARCH}-pc-linux.gz"
gunzip -f cs && chmod +x cs
./cs setup -y >/dev/null
echo "JDK: \$(java -version 2>&1 | head -1)"
EOF

# --- ship the working tree (exclude generated/huge dirs) ---
say "rsync source tree -> instance..."
rsync -az --delete \
  -e "ssh -i ${KEY_FILE} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null" \
  --exclude '/target' --exclude '**/target' --exclude '.bloop' --exclude '.metals' \
  --exclude '/data' --exclude '/results' --exclude '.idea' \
  ./ ubuntu@"$PUBIP":/home/ubuntu/ProtoCatalyst/

# --- run the full benchmark ---
say "running bench.sh ${SF} ${BENCH_ARGS} (this is the long part)..."
"${SSH[@]}" bash -se <<EOF
set -euo pipefail
export PATH="\$HOME/.local/share/coursier/bin:\$PATH"
source ~/.profile 2>/dev/null || true
cd ~/ProtoCatalyst
./scripts/bench.sh ${SF} ${BENCH_ARGS}
EOF

# --- collect results ---
say "collecting results -> ${OUT}"
mkdir -p "$OUT"
REMOTE_DIR="$("${SSH[@]}" "ls -dt ~/ProtoCatalyst/results/*-sf${SF} | head -1")"
rsync -az \
  -e "ssh -i ${KEY_FILE} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null" \
  ubuntu@"$PUBIP":"${REMOTE_DIR}/" "${OUT}/"

say "DONE. results in ${OUT}"
ls -la "$OUT"
