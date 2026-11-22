# 生成 API<26 用的位图启动图标：圆角深底 + 白色双八分音符（与 vector 路径同几何）
import math, os
from PIL import Image, ImageDraw

S = 1024            # 超采样画布
VB = 108.0          # vector viewport
K = S / VB

def p(x, y):
    return (x * K, y * K)

def arc_points(x1, y1, rx, ry, large, sweep, x2, y2, n=96):
    """SVG 端点式圆弧（旋转角 0）采样。"""
    # 端点转圆心
    x1p, y1p = (x1 - x2) / 2.0, (y1 - y2) / 2.0
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s; ry *= s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(num / den, 0.0))
    if large == sweep:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cxp + (x1 + x2) / 2.0
    cy = cyp + (y1 + y2) / 2.0
    # 起始/结束角
    def ang(ux, uy, vx, vy):
        d = (ux * vx + uy * vy) / (math.hypot(ux, uy) * math.hypot(vx, vy))
        d = max(-1.0, min(1.0, d))
        a = math.acos(d)
        if ux * vy - uy * vx < 0:
            a = -a
        return a
    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    pts = []
    for i in range(n + 1):
        t = t1 + dt * i / n
        pts.append((cx + rx * math.cos(t), cy + ry * math.sin(t)))
    return pts

# 与 ic_launcher_foreground.xml 完全一致的路径
path = [(44, 30), (44, 62.5)]
path += arc_points(44, 62.5, 9, 8.5, 1, 0, 50, 70)
path += [(50, 42), (72, 36), (72, 56)]
path += arc_points(72, 56, 9, 8.5, 1, 0, 78, 63.5)
path += [(78, 28)]

img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
# 圆角底：品牌紫 #6750A4（深色电视桌面上也看得见）
bg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
bd = ImageDraw.Draw(bg)
r = int(22 * K)
bd.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=(103, 80, 164, 255))
img = Image.alpha_composite(img, bg)
d = ImageDraw.Draw(img)
d.polygon([p(x, y) for (x, y) in path], fill=(255, 255, 255, 255))

root = r"E:\mp\MusicPlayer\app\src\main\res"
sizes = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
         "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
for folder, px in sizes.items():
    out = os.path.join(root, folder)
    os.makedirs(out, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"), optimize=True)
    print(folder, px, "ok")
