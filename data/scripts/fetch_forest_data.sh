#!/bin/bash
# 拉取合肥市蜀山区的 OSM 林业相关数据（带重试 & 镜像切换）
# 蜀山区 OSM relation id: 3288963
set -e

cd "$(dirname "$0")/../raw"

# 蜀山区大致bbox (south,west,north,east)，WGS84经纬度
BBOX="31.74,117.10,31.95,117.28"

# Overpass 镜像节点（主节点繁忙时依次尝试）
MIRRORS=(
  "https://overpass.kumi.systems/api/interpreter"
  "https://overpass-api.de/api/interpreter"
  "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
)

# 带重试的 Overpass 请求函数
# 用法: overpass_fetch <输出文件> <OverpassQL查询文件>
overpass_fetch() {
  local outfile="$1"
  local queryfile="$2"
  local max_attempts=6

  for attempt in $(seq 1 $max_attempts); do
    local mirror_idx=$(( (attempt - 1) % ${#MIRRORS[@]} ))
    local url="${MIRRORS[$mirror_idx]}"

    echo "   尝试 $attempt/$max_attempts -> $url"
    curl -sf "$url" \
      --data-urlencode "data@${queryfile}" \
      -o "$outfile" \
      --max-time 180 || true

    # 检查是否为合法 JSON（不是 HTML 错误页）
    if python3 -c "import json,sys; d=json.load(open('$outfile')); print(f'  元素数: {len(d.get(\"elements\",[]))}'); sys.exit(0)" 2>/dev/null; then
      echo "   ✓ 获取成功 ($(wc -c < $outfile | tr -d ' ') bytes)"
      return 0
    else
      echo "   ✗ 返回非法内容，等待 20 秒后重试..."
      sleep 20
    fi
  done

  echo "ERROR: $max_attempts 次尝试均失败" >&2
  exit 1
}

# ── 查询1：林地/绿地面 ──────────────────────────────────────────
cat > /tmp/query_forests.txt << QUERYEOF
[out:json][timeout:180];
(
  way["landuse"~"^(forest|grass|meadow|village_green|recreation_ground)$"](${BBOX});
  way["leisure"~"^(park|nature_reserve|garden)$"](${BBOX});
  way["natural"~"^(wood|scrub|heath|grassland)$"](${BBOX});
  relation["landuse"~"^(forest|grass|meadow)$"](${BBOX});
  relation["leisure"~"^(park|nature_reserve|garden)$"](${BBOX});
  relation["natural"~"^(wood|scrub)$"](${BBOX});
);
out geom;
QUERYEOF

echo "==> 1/3 拉取蜀山区林地/绿地面数据（way + relation，带几何）"
overpass_fetch shushan_forests.json /tmp/query_forests.txt

# ── 查询2：林业 POI 节点 ─────────────────────────────────────────
cat > /tmp/query_forest_pois.txt << QUERYEOF
[out:json][timeout:120];
(
  node["landuse"="plant_nursery"](${BBOX});
  node["landuse"="meadow"](${BBOX});
  node["leisure"="nature_reserve"](${BBOX});
  node["leisure"="park"](${BBOX});
  node["leisure"="botanical_garden"](${BBOX});
  node["leisure"="garden"](${BBOX});
  node["natural"="wood"](${BBOX});
  node["natural"="tree"](${BBOX});
  node["natural"="tree_row"](${BBOX});
);
out body;
QUERYEOF

echo "==> 2/3 拉取林业相关 POI（节点）"
overpass_fetch shushan_forest_pois.json /tmp/query_forest_pois.txt

echo "==> 3/3 数据完整性汇总"
python3 - <<'PYEOF'
import json, sys

ok = True
for fname in ["shushan_forests.json", "shushan_forest_pois.json"]:
    try:
        d = json.load(open(fname))
        elements = d.get("elements", [])
        print(f"  {fname}: {len(elements)} 个要素")
    except Exception as e:
        print(f"  {fname}: 读取失败 - {e}", file=sys.stderr)
        ok = False

sys.exit(0 if ok else 1)
PYEOF

echo "==> 完成，文件位于 data/raw/"
