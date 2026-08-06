#!/bin/bash
# 拉取蜀山区下辖街道/镇行政边界 (admin_level=8 或 relation boundary=administrative in Shushan relation 3288963)
set -e

cd "$(dirname "$0")/../raw"

SHUSHAN_RELATION_ID=3288963

echo "==> 拉取蜀山区各街道/镇边界 (admin_level=8)"
# 使用 Overpass QL 获取蜀山区 relation(3288963) 内部包含的 admin_level=8 边界
cat > /tmp/query_subdistricts.txt << QUERYEOF
[out:json][timeout:120];
relation(${SHUSHAN_RELATION_ID});
map_to_area->.shushan;
(
  relation["boundary"="administrative"]["admin_level"~"^(8|9)$"](area.shushan);
);
out geom;
QUERYEOF

# 使用带镜像重试的拉取逻辑
MIRRORS=(
  "https://overpass.kumi.systems/api/interpreter"
  "https://overpass-api.de/api/interpreter"
  "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
)

outfile="shushan_subdistricts.json"
max_attempts=4

for attempt in $(seq 1 $max_attempts); do
  mirror_idx=$(( (attempt - 1) % ${#MIRRORS[@]} ))
  url="${MIRRORS[$mirror_idx]}"
  echo "   尝试 $attempt/$max_attempts -> $url"
  curl -sf "$url" --data-urlencode "data@/tmp/query_subdistricts.txt" -o "$outfile" --max-time 120 || true

  if python3 -c "import json,sys; d=json.load(open('$outfile')); print(f'  获取到 {len(d.get(\"elements\",[]))} 个街道边界要素'); sys.exit(0)" 2>/dev/null; then
    echo "   ✓ 获取成功 ($(wc -c < $outfile | tr -d ' ') bytes)"
    break
  else
    echo "   ✗ 返回非法内容，20 秒后重试..."
    sleep 20
  fi
done

echo "==> 检查获取到的街道数据"
python3 - <<'PYEOF'
import json
d = json.load(open('shushan_subdistricts.json'))
elems = d.get('elements', [])
print(f"包含 {len(elems)} 个街道/镇边界要素:")
for e in elems:
    tags = e.get('tags', {})
    name = tags.get('name', '未命名')
    admin_level = tags.get('admin_level', '?')
    print(f"  - {name} (admin_level={admin_level})")
PYEOF

echo "==> 完成，文件位于 data/raw/shushan_subdistricts.json"
