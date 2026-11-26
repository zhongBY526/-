# -*- coding: utf-8 -*-
"""把 WebFetch 缓存的网页文本清洗成入库用 md 资料（保留正文，去掉导航/页脚/表格竖线）"""
import re, sys, pathlib
sys.stdout.reconfigure(encoding='utf-8')

CACHE = pathlib.Path(r"C:\Users\itsuka\.qoder-cn\cache\projects\Project-Study-405819db\agent-tools\cb508129")
OUT = pathlib.Path(r"E:\Project-Study\materials")
OUT.mkdir(exist_ok=True)

# (缓存文件, 输出名, 标题, 来源URL, 正文起始标记regex)
DOCS = [
    ("b3a86fff.txt", "01_党政机关公文处理工作条例.md", "党政机关公文处理工作条例",
     "https://www.amac.org.cn/hydj/djyl/202112/t20211222_15472.html", r"第一章"),
    ("432fe80f.txt", "02_中华人民共和国政府信息公开条例.md", "中华人民共和国政府信息公开条例（国务院令第711号）",
     "https://www.jcs.gov.cn/ggzyjy/zwgk/zfxxgkzd/art/2024/art_46c1442b6822471f805eda3b729ee62c.html", r"中华人民共和国国务院令"),
    ("2a1797c9.txt", "03_优化营商环境条例.md", "优化营商环境条例（国务院令第722号）",
     "http://gdcec.gd.gov.cn/spzx/zcfg/flfg/content/post_4229264.html", r"中华人民共和国国务院令"),
    ("f71a75a5.txt", "04_医疗器械监督管理条例.md", "医疗器械监督管理条例（2024年修订）",
     "http://gdcec.gd.gov.cn/spzx/zcfg/flfg/content/post_4660189.html", r"[（(]2000年"),
    ("43e4e46a.txt", "05_甘肃省行政规范性文件管理办法.md", "甘肃省行政规范性文件管理办法（省政府令第158号）",
     "https://www.huining.gov.cn/xxgk/bmxxgk/yjglj/fdzdgknr/zczxwdk/art/2026/art_f47e98cf2ebd460c847c5f6373214011.html", r"甘肃省人民政府令第158号"),
    ("9a4a336e.txt", "06_安徽省行政规范性文件管理办法.md", "安徽省行政规范性文件管理办法",
     "https://jtysj.chizhou.gov.cn/Content/show/714398.html", r"第一条"),
    ("54852b35.txt", "07_山东省行政规范性文件制定和监督管理办法.md", "山东省行政规范性文件制定和监督管理办法（省政府令第353号）",
     "http://www.tengzhou.gov.cn/zzq/zfbm/xzspfwj/zcfg/202601/t20260126_2238521.html", r"[（(]2023年"),
    ("5d5c01e3.txt", "08_江西省行政规范性文件管理办法.md", "江西省行政规范性文件管理办法",
     "https://www.shicheng.gov.cn/scxxxgk/gfxwjsc258/202608/7988032ace2344ec9decdb163136cb43.shtml", r"第一条"),
    ("c24cccbd.txt", "09_云南省行政规范性文件管理办法.md", "云南省行政规范性文件管理办法（省政府令第234号）",
     "https://www.dhms.gov.cn/Web/_F0_0_688FXFOQF58D8B5E60EF4F358B.htm", r"《云南省行政规范性文件管理办法》已经"),
    ("9f58b0d1.txt", "10_整治形式主义为基层减负若干规定.md", "整治形式主义为基层减负若干规定",
     "http://www.hgjjjc.gov.cn/Print.aspx?id=42577", r"[（(]2024年7月30日"),
    ("cde8d162.txt", "11_重大行政决策程序暂行条例.md", "重大行政决策程序暂行条例（国务院令第713号）",
     "http://jzw.sc.gov.cn/scjczx/xgzc/2025/10/23/52de0f2ab8de44428c90679d27ca15f5.shtml", r"中华人民共和国国务院令"),
    ("a878c71c.txt", "21_医疗器械经营监督管理办法.md", "医疗器械经营监督管理办法（国家市场监督管理总局令第54号）",
     "http://www.wuxue.gov.cn/zwgk/grassroots/6636855/1075021.html", r"[（(]2022年3月10日"),
]

FOOTER_MARKERS = ["附件：本省系统网站", "主办单位：", "版权所有", "粤ICP", "分享：", "打印本页",
                  "关闭窗口", "网站导航", "相关链接：", "【字体", "扫一扫", "浏览次数",
                  "打印本文", "Rights Reserved", "All Rights", "Copyright", "扫码关注", "友情链接"]
JUNK_LINE_PAT = re.compile(
    r"^(您的位置|首页|索引号|生成日期|文\s*号|关键字|所属机构|发布机构|信息来源|发布时间|字体：|"
    r"\[大中小\]|\[打印\]|附件：\s*$|\|?\s*---|时间：\d{4}|来源：\s*$)")

def clean(text):
    # 去掉图片与链接语法，保留链接文字
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    lines = []
    for ln in text.splitlines():
        s = ln.strip()
        if not s:
            continue
        if JUNK_LINE_PAT.match(s):
            continue
        # 表格竖线转普通文本（保留单元格内容）
        if s.startswith("|"):
            cells = [c.strip() for c in s.strip("|").split("|")]
            if all(re.fullmatch(r"-{3,}", c) for c in cells if c):
                continue
            s = " ".join(c for c in cells if c)
        lines.append(s)
    return "\n".join(lines)

for fname, outname, title, url, start in DOCS:
    raw = (CACHE / fname).read_text(encoding="utf-8", errors="replace")
    m = re.search(start, raw)
    if not m:
        print(f"[WARN] {outname}: 找不到正文起始标记 {start}，用全文清洗")
        body = raw
    else:
        body = raw[m.start():]
    # 截断页脚
    cut = len(body)
    for marker in FOOTER_MARKERS:
        i = body.find(marker)
        if i != -1:
            cut = min(cut, i)
    body = clean(body[:cut])
    header = f"# {title}\n\n> 来源：{url}\n> 说明：政府网站公开发布文本，供知识库评测学习使用。\n\n"
    (OUT / outname).write_text(header + body + "\n", encoding="utf-8")
    print(f"[OK] {outname}  {len(body)} 字  开头: {body[:40]!r}  结尾: {body[-40:]!r}")