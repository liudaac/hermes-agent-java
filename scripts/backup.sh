#!/bin/bash
#
# Hermes Agent Java — Data Backup Script
#
# Usage:
#   ./scripts/backup.sh              # Backup to default location
#   ./scripts/backup.sh /backups     # Backup to custom directory
#

set -euo pipefail

# Configuration
HERMES_HOME="${HERMES_HOME:-/data}"
BACKUP_BASE_DIR="${1:-./backups}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="${BACKUP_BASE_DIR}/hermes_backup_${TIMESTAMP}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"

echo "=== Hermes Agent Backup ==="
echo "Source: ${HERMES_HOME}"
echo "Destination: ${BACKUP_DIR}"
echo "Timestamp: ${TIMESTAMP}"

# Create backup directory
mkdir -p "${BACKUP_DIR}"

# Check if source exists
if [ ! -d "${HERMES_HOME}" ]; then
    echo "ERROR: HERMES_HOME directory does not exist: ${HERMES_HOME}"
    exit 1
fi

# Backup business data (workspaces, teams, scenarios, runs, etc.)
if [ -d "${HERMES_HOME}/business" ]; then
    echo "[1/4] Backing up business data..."
    cp -a "${HERMES_HOME}/business" "${BACKUP_DIR}/business"
fi

# Backup tenant data
if [ -d "${HERMES_HOME}/tenants" ]; then
    echo "[2/4] Backing up tenant data..."
    cp -a "${HERMES_HOME}/tenants" "${BACKUP_DIR}/tenants"
fi

# Backup configuration
if [ -d "${HERMES_HOME}/config" ]; then
    echo "[3/4] Backing up configuration..."
    cp -a "${HERMES_HOME}/config" "${BACKUP_DIR}/config"
fi

# Backup session database
if [ -f "${HERMES_HOME}/sessions.db" ]; then
    echo "[4/4] Backing up session database..."
    cp -a "${HERMES_HOME}/sessions.db" "${BACKUP_DIR}/sessions.db"
fi

# Create backup manifest
cat > "${BACKUP_DIR}/manifest.txt" <<EOF
Hermes Agent Java Backup
========================
Timestamp: ${TIMESTAMP}
Hostname: $(hostname)
Source: ${HERMES_HOME}
Backup Version: 1.0
EOF

echo ""
echo "Backup completed: ${BACKUP_DIR}"
echo "Size: $(du -sh "${BACKUP_DIR}" | cut -f1)"

# Clean up old backups
echo ""
echo "Cleaning up backups older than ${RETENTION_DAYS} days..."
find "${BACKUP_BASE_DIR}" -maxdepth 1 -name "hermes_backup_*" -type d -mtime +${RETENTION_DAYS} -print -exec rm -rf {} \; 2>/dev/null || true

echo "Backup finished successfully."
