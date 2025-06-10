#!/bin/bash
# Usage Examples for Terabyte File Downloads

# 1. Start a terabyte download
curl -X POST http://localhost:8080/api/terabyte/download \
  -H "Content-Type: application/json" \
  -d '{
    "s3Key": "massive-datasets/1tb-file.zip",
    "nasFileName": "1tb-file.zip"
  }'

# Response: {"downloadId":"123e4567-e89b-12d3-a456-426614174000","status":"STARTED"}

# 2. Monitor download progress
DOWNLOAD_ID="123e4567-e89b-12d3-a456-426614174000"
curl http://localhost:8080/api/terabyte/progress/$DOWNLOAD_ID | jq

# Example response:
# {
#   "downloadId": "123e4567-e89b-12d3-a456-426614174000",
#   "totalSize": 1099511627776,
#   "bytesDownloaded": 107374182400,
#   "status": "DOWNLOADING",
#   "progressPercentage": 9.77,
#   "downloadSpeed": 104857600,
#   "startedAt": "2025-06-10T10:00:00Z",
#   "lastUpdated": "2025-06-10T11:00:00Z"
# }

# 3. Monitor all active downloads
curl http://localhost:8080/api/terabyte/progress | jq

# 4. Check application health
curl http://localhost:8080/api/actuator/health | jq

# 5. Monitor system resources during download
#!/bin/bash
# monitoring-script.sh

DOWNLOAD_ID=$1
if [ -z "$DOWNLOAD_ID" ]; then
    echo "Usage: $0 <download-id>"
    exit 1
fi

echo "Monitoring download: $DOWNLOAD_ID"
echo "Press Ctrl+C to stop monitoring"

while true; do
    # Get download progress
    PROGRESS=$(curl -s http://localhost:8080/api/terabyte/progress/$DOWNLOAD_ID)
    
    if [ $? -eq 0 ]; then
        PERCENT=$(echo $PROGRESS | jq -r '.progressPercentage // 0')
        SPEED=$(echo $PROGRESS | jq -r '.downloadSpeed // 0')
        STATUS=$(echo $PROGRESS | jq -r '.status // "UNKNOWN"')
        DOWNLOADED=$(echo $PROGRESS | jq -r '.bytesDownloaded // 0')
        TOTAL=$(echo $PROGRESS | jq -r '.totalSize // 0')
        
        # Convert bytes to GB
        DOWNLOADED_GB=$(echo "scale=2; $DOWNLOADED / 1024 / 1024 / 1024" | bc)
        TOTAL_GB=$(echo "scale=2; $TOTAL / 1024 / 1024 / 1024" | bc)
        SPEED_MBPS=$(echo "scale=2; $SPEED / 1024 / 1024" | bc)
        
        # System stats
        MEMORY=$(free -h | awk '/^Mem:/ {print $3 "/" $2}')
        CPU=$(top -bn1 | grep "Cpu(s)" | awk '{print $2}' | cut -d'%' -f1)
        DISK_USAGE=$(df -h /mnt/nas | awk 'NR==2 {print $3 "/" $2 " (" $5 " used)"}')
        
        clear
        echo "=========================================="
        echo "TERABYTE DOWNLOAD MONITOR"
        echo "=========================================="
        echo "Download ID: $DOWNLOAD_ID"
        echo "Status: $STATUS"
        echo "Progress: ${PERCENT}% (${DOWNLOADED_GB}GB / ${TOTAL_GB}GB)"
        echo "Speed: ${SPEED_MBPS} MB/s"
        echo ""
        echo "SYSTEM RESOURCES:"
        echo "Memory Usage: $MEMORY"
        echo "CPU Usage: ${CPU}%"
        echo "NAS Disk Usage: $DISK_USAGE"
        echo ""
        echo "Last Updated: $(date)"
        
        if [ "$STATUS" = "COMPLETED" ] || [ "$STATUS" = "FAILED" ]; then
            echo ""
            echo "Download $STATUS!"
            break
        fi
    else
        echo "Failed to get download status"
    fi
    
    sleep 30
done

# 6. NAS Performance Optimization Script
#!/bin/bash
# optimize-nas.sh - Run before starting large downloads

echo "Optimizing system for terabyte downloads..."

# Increase network buffer sizes
echo 'net.core.rmem_max = 134217728' >> /etc/sysctl.conf
echo 'net.core.wmem_max = 134217728' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_rmem = 4096 87380 134217728' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_wmem = 4096 65536 134217728' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_congestion_control = bbr' >> /etc/sysctl.conf

# Apply network optimizations
sysctl -p

# Increase file descriptor limits
echo "* soft nofile 65536" >> /etc/security/limits.conf
echo "* hard nofile 65536" >> /etc/security/limits.conf

# Mount NAS with optimized options (adjust for your NAS type)
# For NFS:
# mount -t nfs -o rsize=1048576,wsize=1048576,hard,intr nas-server:/export /mnt/nas

# For SMB/CIFS:
# mount -t cifs //nas-server/share /mnt/nas -o rsize=1048576,wsize=1048576,cache=strict

echo "System optimization completed!"

# 7. Cleanup script for failed downloads
#!/bin/bash
# cleanup-failed.sh

echo "Cleaning up failed downloads..."

# Clean up temp files older than 24 hours
find /mnt/nas/temp -name "*.tmp" -mtime +1 -delete

# Clean up application logs older than 30 days
find ./logs -name "*.log*" -mtime +30 -delete

echo "Cleanup completed!"

# 8. Pre-download validation script
#!/bin/bash
# validate-download.sh

S3_KEY=$1
NAS_FILENAME=$2

if [ -z "$S3_KEY" ] || [ -z "$NAS_FILENAME" ]; then
    echo "Usage: $0 <s3-key> <nas-filename>"
    exit 1
fi

echo "Validating download prerequisites..."

# Check S3 file exists and get size
AWS_OUTPUT=$(aws s3api head-object --bucket your-bucket-name --key "$S3_KEY" 2>/dev/null)
if [ $? -ne 0 ]; then
    echo "ERROR: S3 file not found or not accessible: $S3_KEY"
    exit 1
fi

FILE_SIZE=$(echo $AWS_OUTPUT | jq -r '.ContentLength')
FILE_SIZE_GB=$(echo "scale=2; $FILE_SIZE / 1024 / 1024 / 1024" | bc)

echo "S3 file size: ${FILE_SIZE_GB}GB"

# Check available NAS space
AVAILABLE_SPACE=$(df /mnt/nas | awk 'NR==2 {print $4}')
REQUIRED_SPACE=$((FILE_SIZE / 1024 + 107374182400)) # File size + 100GB buffer

if [ $AVAILABLE_SPACE -lt $REQUIRED_SPACE ]; then
    AVAILABLE_GB=$(echo "scale=2; $AVAILABLE_SPACE * 1024 / 1024 / 1024" | bc)
    REQUIRED_GB=$(echo "scale=2; $REQUIRED_SPACE / 1024 / 1024 / 1024" | bc)
    echo "ERROR: Insufficient NAS space. Available: ${AVAILABLE_GB}GB, Required: ${REQUIRED_GB}GB"
    exit 1
fi

# Check if file already exists
if [ -f "/mnt/nas/downloads/$NAS_FILENAME" ]; then
    echo "WARNING: File already exists: /mnt/nas/downloads/$NAS_FILENAME"
    read -p "Continue anyway? (y/n): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

echo "Validation passed! Ready to download."
echo "File: $S3_KEY -> $NAS_FILENAME"
echo "Size: ${FILE_SIZE_GB}GB"

# Start the download
curl -X POST http://localhost:8080/api/terabyte/download \
  -H "Content-Type: application/json" \
  -d "{\"s3Key\":\"$S3_KEY\",\"nasFileName\":\"$NAS_FILENAME