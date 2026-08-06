#!/bin/bash
# 拉取合肥市蜀山区范围内的建筑物 Footprint (way["building"])
set -e

cd "$(dirname "$0")/../raw"

SHUSHAN_RELATION_ID=3288963
# 蜀山区核心城区 bbox (south,west,north,east)
BBOX="31.80,117.14,31.90,117.27"

MIRRORS=(
  "https://overpass.kumi.systems/api/interpreter"
  "https://overpass-api.de/api/interpreter"
  "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
)

outfile="shushan_buildings.json"
max_attempts=4

cat > /tmp/query_buildings.txt << QUERYEOF
[out:json][timeout:180];
(
  way["building"](${BBOX});
);
out geom;
QUERYEOF

echo "==> 拉取蜀山区核心区建筑物 footprint (Overpass API)"

for attempt in $(seq 1 $max_attempts); do
  mirror_idx=$(( (attempt - 1) % ${#MIRRORS[@]} ))
  url="${MIRRORS[$mirror_idx]}"
  echo "   尝试 $attempt/$max_attempts -> $url"
  curl -sf "$url" --data-urlencode "data@/tmp/query_buildings.txt" -o "$outfile" --max-time 180 || true

  if python3 -c "import json,sys; d=json.load(open('$outfile')); print(f'  获取到 {len(d.get(\"elements\",[]))} 个建筑物要素'); sys.exit(0)" 2>/dev/null; then
    echo "   ✓ 获取成功 ($(wc -c < $outfile | tr -d ' ') bytes)"
    break
  else
    echo "   ✗ 返回非法内容，等待 15 秒后重试..."
    sleep 15
  fi
done

python3 - <<'PYEOF'
import json, sys
try:
    d = json.load(open('shushan_buildings.json'))
    elems = d.get('elements', [])
    print(f"==> 总结: 共计 {len(elems)} 个建筑物要素")
except Exception as e:
    print(f"==> 错误: 读取失败 {e}", file=sys.stderr)
    sys.exit(1)
PYEOF

echo "==> 完成，文件位于 data/raw/shushan_buildings.json"
