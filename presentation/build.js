/* Capstone weekly presentation — Wi-Fi Heatmap Simulator (구현 중심 개편판)
 * 초점: (1) FDTD를 어떻게 구현했나 — 직관적으로  (2) 실측 시각화·성능  (3) DPM↔FDTD 협업
 * 용어는 쉬운 비유 + (전문용어) 병기. 일반 학생·교수 모두 대상.
 * Run: NODE_PATH=/opt/homebrew/lib/node_modules node build.js
 */
const pptxgen = require("pptxgenjs");

const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE";            // 13.33 x 7.5
pres.author = "Wi-Fi Heatmap Simulator Team";
pres.title  = "Wi-Fi Heatmap Simulator — 캡스톤 디자인";

const W = 13.33, H = 7.5;

const C = {
  navy:   "0C1B2A", navy2:  "12273B", ink:    "13283B", body:   "33475B",
  muted:  "6B7C8F", light:  "F3F7FA", card:   "FFFFFF", line:   "DCE6EE",
  teal:   "1C7293", teal2:  "14A0B0", cyan:   "38BDF8",
  orange: "F2792B", amber:  "F4B23E", green:  "3FA86A", red:    "D9534F",
};
const FONT  = "AppleGothic";   // 단일 페이스 .ttf — 렌더 결정성
const LATIN = "Arial";

const shadow = () => ({ type: "outer", color: "0C1B2A", blur: 9, offset: 3, angle: 90, opacity: 0.18 });
function bg(slide, color) { slide.background = { color }; }

function footer(slide, n) {
  slide.addText("Wi-Fi Heatmap Simulator · 캡스톤 디자인", { x: 0.6, y: H - 0.42, w: 8, h: 0.3, fontFace: FONT, fontSize: 9, color: C.muted, align: "left", margin: 0 });
  slide.addText(String(n).padStart(2, "0"), { x: W - 1.1, y: H - 0.42, w: 0.5, h: 0.3, fontFace: FONT, fontSize: 9, color: C.muted, align: "right", margin: 0 });
}
function header(slide, part, title) {
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 0.6, y: 0.45, w: 2.1, h: 0.34, rectRadius: 0.17, fill: { color: C.teal } });
  slide.addText(part, { x: 0.6, y: 0.45, w: 2.1, h: 0.34, fontFace: FONT, fontSize: 10.5, bold: true, color: "FFFFFF", align: "center", valign: "middle", charSpacing: 1, margin: 0 });
  slide.addText(title, { x: 0.58, y: 0.9, w: 12.1, h: 0.7, fontFace: FONT, fontSize: 26, bold: true, color: C.ink, align: "left", valign: "middle", margin: 0 });
}
function card(slide, x, y, w, h, bar) {
  slide.addShape(pres.shapes.RECTANGLE, { x, y, w, h, fill: { color: C.card }, line: { color: C.line, width: 1 }, shadow: shadow() });
  if (bar) slide.addShape(pres.shapes.RECTANGLE, { x, y, w: 0.09, h, fill: { color: bar } });
}
// 작은 번호 동그라미 + 제목
function numTitle(slide, x, y, n, title, color, w) {
  slide.addShape(pres.shapes.OVAL, { x, y, w: 0.46, h: 0.46, fill: { color } });
  slide.addText(String(n), { x, y, w: 0.46, h: 0.46, fontFace: FONT, fontSize: 15, bold: true, color: "FFFFFF", align: "center", valign: "middle", margin: 0 });
  slide.addText(title, { x: x + 0.62, y: y - 0.02, w: w || 4.5, h: 0.5, fontFace: FONT, fontSize: 15, bold: true, color: C.ink, valign: "middle", margin: 0 });
}

// ============================================================
// 1 — TITLE
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.navy);
  const cx = 11.4, cy = 1.5;
  [3.2, 2.4, 1.6, 0.9].forEach((r, i) => s.addShape(pres.shapes.OVAL, { x: cx - r, y: cy - r, w: r * 2, h: r * 2, fill: { type: "solid", color: C.navy, transparency: 100 }, line: { color: i % 2 ? C.teal : C.cyan, width: 1, transparency: 55 + i * 8 } }));
  s.addShape(pres.shapes.OVAL, { x: cx - 0.13, y: cy - 0.13, w: 0.26, h: 0.26, fill: { color: C.orange } });

  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 0.85, y: 1.55, w: 3.0, h: 0.42, rectRadius: 0.21, fill: { color: C.navy2 }, line: { color: C.teal, width: 1 } });
  s.addText("CAPSTONE DESIGN · WEEKLY", { x: 0.85, y: 1.55, w: 3.0, h: 0.42, fontFace: FONT, fontSize: 11, bold: true, color: C.cyan, align: "center", valign: "middle", charSpacing: 1, margin: 0 });

  s.addText("Wi-Fi Heatmap Simulator", { x: 0.8, y: 2.25, w: 10.5, h: 1.0, fontFace: LATIN, fontSize: 46, bold: true, color: "FFFFFF", align: "left", margin: 0 });
  s.addText("구현 이야기 — 전파 시뮬레이션(FDTD)과 공유기 자리 추천(DPM+FDTD)을 어떻게 만들었나", {
    x: 0.82, y: 3.3, w: 11.5, h: 0.5, fontFace: FONT, fontSize: 17, bold: true, color: C.cyan, align: "left", margin: 0 });
  s.addText("호수에 돌을 던지면 물결이 퍼지듯, 컴퓨터 안에서 전파를 한 칸씩 실제로 퍼뜨려 계산하고 — 그 결과로 최적의 공유기 위치를 자동으로 찾습니다.", {
    x: 0.82, y: 3.95, w: 10.2, h: 0.8, fontFace: FONT, fontSize: 13, color: "AFC3D4", align: "left", valign: "top", margin: 0, lineSpacingMultiple: 1.1 });

  s.addShape(pres.shapes.LINE, { x: 0.85, y: 5.55, w: 6.6, h: 0, line: { color: C.teal, width: 1, transparency: 40 } });
  s.addText([{ text: "[ 팀명 ]", options: { color: "FFFFFF", bold: true } }, { text: "   ·   [ 발표자 ]   ·   [ N주차 ]   ·   [ 과목명 ]", options: { color: C.muted } }],
    { x: 0.85, y: 5.7, w: 11, h: 0.4, fontFace: FONT, fontSize: 13, align: "left", margin: 0 });
})();

// ============================================================
// 2 — 전체 동작 한눈에 (파이프라인)
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.light);
  header(s, "한눈에 보기", "이 프로그램은 이렇게 동작합니다");
  s.addText("도면을 올리면 → 와이파이 쓸 공간을 고르고 → 공유기 자리를 자동 추천 → 전파 지도(히트맵)로 확인. 그 안에서 두 계산 엔진이 협력합니다.", {
    x: 0.6, y: 1.6, w: 12.1, h: 0.4, fontFace: FONT, fontSize: 13, color: C.body, margin: 0 });

  const steps = [
    ["1", "공간 그리기", "도면 업로드 → 실제 크기 맞추기\n→ 벽 그리기 → 쓸 공간 선택", C.teal],
    ["2", "빠른 추정", "후보 위치들을 빠른 모델(DPM)로\n채점 — 최소 공유기로 최대 커버", C.amber],
    ["3", "정밀 검증", "추천 자리를 전파 시뮬(FDTD)로\n실제 검증 — 부족하면 옮겨 재검증", C.orange],
    ["4", "결과 확인", "전파 지도 자동 생성 +\n고른 공간 기준 품질 측정", C.green],
  ];
  const pw = 2.74, gap = 0.37, x0 = 0.6, y0 = 2.2, ph = 2.0;
  steps.forEach((st, i) => {
    const x = x0 + i * (pw + gap);
    card(s, x, y0, pw, ph, st[3]);
    s.addShape(pres.shapes.OVAL, { x: x + 0.28, y: y0 + 0.28, w: 0.5, h: 0.5, fill: { color: st[3] } });
    s.addText(st[0], { x: x + 0.28, y: y0 + 0.28, w: 0.5, h: 0.5, fontFace: FONT, fontSize: 17, bold: true, color: "FFFFFF", align: "center", valign: "middle", margin: 0 });
    s.addText(st[1], { x: x + 0.92, y: y0 + 0.3, w: pw - 1.05, h: 0.5, fontFace: FONT, fontSize: 15, bold: true, color: C.ink, valign: "middle", margin: 0 });
    s.addText(st[2], { x: x + 0.3, y: y0 + 0.95, w: pw - 0.55, h: 0.95, fontFace: FONT, fontSize: 11.5, color: C.body, valign: "top", margin: 0, lineSpacingMultiple: 1.05 });
    if (i < steps.length - 1) s.addShape(pres.shapes.LINE, { x: x + pw + 0.05, y: y0 + ph / 2, w: gap - 0.1, h: 0, line: { color: C.muted, width: 1.5, endArrowType: "triangle" } });
  });

  // 두 엔진 강조
  card(s, 0.6, 4.55, 6.0, 2.05, C.amber);
  s.addText("DPM — 빠른 추정 엔진", { x: 0.85, y: 4.75, w: 5.5, h: 0.4, fontFace: FONT, fontSize: 15, bold: true, color: C.ink, margin: 0 });
  s.addText("전파가 ‘가장 잘 통하는 길’만 빠르게 계산. 1초에 수많은 후보를 평가해 좋은 자리를 추려냄. (지배경로 모델, Dominant Path Model)", {
    x: 0.85, y: 5.2, w: 5.5, h: 1.2, fontFace: FONT, fontSize: 12.5, color: C.body, valign: "top", margin: 0, lineSpacingMultiple: 1.1 });

  card(s, 6.73, 4.55, 6.0, 2.05, C.orange);
  s.addText("FDTD — 정밀 검증 엔진", { x: 6.98, y: 4.75, w: 5.5, h: 0.4, fontFace: FONT, fontSize: 15, bold: true, color: C.ink, margin: 0 });
  s.addText("전파를 물결처럼 실제로 퍼뜨려 계산. 느리지만 정확 — 추려진 자리가 진짜 맞는지 최종 확인. (전자기파 수치 시뮬레이션)", {
    x: 6.98, y: 5.2, w: 5.5, h: 1.2, fontFace: FONT, fontSize: 12.5, color: C.body, valign: "top", margin: 0, lineSpacingMultiple: 1.1 });
  footer(s, 2);
})();

// ============================================================
// 3 — FDTD 쉽게 이해하기 (비유)
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.light);
  header(s, "PART 1 · FDTD", "FDTD, 쉽게 말하면 — 물결을 한 칸씩 퍼뜨리기");

  // left: 4-step analogy
  card(s, 0.6, 1.7, 6.4, 4.95, C.teal);
  const steps = [
    ["공간을 바둑판처럼 나눈다", "방을 아주 작은 사각형 칸으로 분할 (격자, grid)", C.teal],
    ["각 칸에 전파 세기를 저장", "칸마다 전기장·자기장 값을 기록", C.teal2],
    ["옆 칸으로 조금씩 전달", "아주 짧은 시간마다 이웃 칸으로 값을 넘기며 갱신 (시간 전진)", C.amber],
    ["수천 번 반복", "물결이 퍼지듯 전파가 방 전체로 번지는 모습이 재현됨", C.orange],
  ];
  let yy = 2.0;
  steps.forEach((st, i) => {
    s.addShape(pres.shapes.OVAL, { x: 0.85, y: yy, w: 0.44, h: 0.44, fill: { color: st[2] } });
    s.addText(String(i + 1), { x: 0.85, y: yy, w: 0.44, h: 0.44, fontFace: FONT, fontSize: 14, bold: true, color: "FFFFFF", align: "center", valign: "middle", margin: 0 });
    s.addText(st[0], { x: 1.42, y: yy - 0.04, w: 5.4, h: 0.4, fontFace: FONT, fontSize: 14.5, bold: true, color: C.ink, valign: "middle", margin: 0 });
    s.addText(st[1], { x: 1.42, y: yy + 0.38, w: 5.4, h: 0.55, fontFace: FONT, fontSize: 11.8, color: C.body, valign: "top", margin: 0, lineSpacingMultiple: 1.03 });
    yy += 1.12;
  });

  // right: grid + ripple illustration
  card(s, 7.13, 1.7, 5.6, 4.95, C.cyan);
  s.addText("호수에 돌을 던진 것처럼", { x: 7.38, y: 1.9, w: 5.1, h: 0.4, fontFace: FONT, fontSize: 15, bold: true, color: C.ink, margin: 0 });
  // grid
  const gx0 = 7.55, gy0 = 2.55, cell = 0.42, cols = 11, rows = 8;
  for (let i = 0; i <= cols; i++) s.addShape(pres.shapes.LINE, { x: gx0 + i * cell, y: gy0, w: 0, h: rows * cell, line: { color: "CFE3EE", width: 0.75 } });
  for (let j = 0; j <= rows; j++) s.addShape(pres.shapes.LINE, { x: gx0, y: gy0 + j * cell, w: cols * cell, h: 0, line: { color: "CFE3EE", width: 0.75 } });
  // ripple rings from a source cell
  const scx = gx0 + 3.5 * cell, scy = gy0 + 4 * cell;
  [1.55, 1.15, 0.78, 0.45].forEach((r, i) => s.addShape(pres.shapes.OVAL, { x: scx - r, y: scy - r, w: r * 2, h: r * 2, fill: { type: "solid", color: C.card, transparency: 100 }, line: { color: i % 2 ? C.teal : C.teal2, width: 1.6, transparency: 25 + i * 12 } }));
  s.addShape(pres.shapes.OVAL, { x: scx - 0.11, y: scy - 0.11, w: 0.22, h: 0.22, fill: { color: C.orange } });
  // neighbor arrows
  [[1,0],[-1,0],[0,1],[0,-1]].forEach(d => s.addShape(pres.shapes.LINE, { x: scx, y: scy, w: d[0]*cell, h: d[1]*cell, line: { color: C.orange, width: 1.6, endArrowType: "triangle" } }));
  s.addText("한 칸의 값이 매 순간 이웃 칸으로 전달 → 잔물결처럼 전파가 번져 나감", { x: 7.38, y: 6.05, w: 5.1, h: 0.5, fontFace: FONT, fontSize: 11.5, color: C.body, valign: "top", margin: 0, lineSpacingMultiple: 1.03 });

  footer(s, 3);
})();

// ============================================================
// 4 — 우리 구현의 핵심 3가지 (+벽을 숫자로)
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.light);
  header(s, "PART 1 · FDTD", "안정적인 시뮬레이션을 위한 3가지 장치");

  const cols = [
    { bar: C.teal, t: "톱니바퀴처럼 엇갈린 배치", term: "Yee 격자",
      body: "전기장과 자기장을 같은 칸이 아니라 반 칸씩 어긋나게 둡니다. 톱니바퀴가 맞물리듯, 계산이 안정적이고 정확해집니다." },
    { bar: C.orange, t: "안전한 시간 간격", term: "CFL 조건",
      body: "한 스텝의 시간이 너무 길면 물결이 칸을 건너뛰어 계산이 폭주(발산)합니다. 그래서 한계의 0.90배로만 전진합니다.",
      code: "Δt = 0.90 · Δx / (c·√2)" },
    { bar: C.green, t: "경계의 메아리 차단", term: "PML 흡수층",
      body: "방 끝에서 물결이 튕겨 되돌아오면 가짜 간섭이 생깁니다. 가장자리에 ‘흡수 스펀지’ 층을 둬 끝없는 공간처럼 만듭니다." },
  ];
  const cw = 3.93, cg = 0.17, y0 = 1.8, ch = 3.35;
  cols.forEach((col, i) => {
    const x = 0.6 + i * (cw + cg);
    card(s, x, y0, cw, ch, col.bar);
    s.addShape(pres.shapes.OVAL, { x: x + 0.3, y: y0 + 0.32, w: 0.42, h: 0.42, fill: { color: col.bar } });
    s.addText(String(i + 1), { x: x + 0.3, y: y0 + 0.32, w: 0.42, h: 0.42, fontFace: FONT, fontSize: 14, bold: true, color: "FFFFFF", align: "center", valign: "middle", margin: 0 });
    // 제목(굵게) + 용어(아래줄, 음영) — 2줄 고정으로 줄바꿈 깨짐 방지
    s.addText(col.t, { x: x + 0.84, y: y0 + 0.28, w: cw - 1.05, h: 0.4, fontFace: FONT, fontSize: 13.5, bold: true, color: C.ink, valign: "middle", margin: 0 });
    s.addText(col.term, { x: x + 0.84, y: y0 + 0.66, w: cw - 1.05, h: 0.3, fontFace: FONT, fontSize: 10.5, color: C.muted, valign: "middle", margin: 0 });
    s.addText(col.body, { x: x + 0.3, y: y0 + 1.2, w: cw - 0.6, h: 1.5, fontFace: FONT, fontSize: 12, color: C.body, valign: "top", margin: 0, lineSpacingMultiple: 1.1 });
    // 수식 박스: 세 카드 모두 같은 하단 위치 — 2번만 채우고 나머진 비워 균형 유지
    if (col.code) {
      s.addShape(pres.shapes.RECTANGLE, { x: x + 0.3, y: y0 + 2.62, w: cw - 0.6, h: 0.5, fill: { color: "0E2233" } });
      s.addText(col.code, { x: x + 0.3, y: y0 + 2.62, w: cw - 0.6, h: 0.5, fontFace: "Consolas", fontSize: 12.5, bold: true, color: C.cyan, align: "center", valign: "middle", margin: 0 });
    }
  });

  // 벽을 숫자로 band — 제목·본문을 왼쪽 정렬로 자연스럽게 이어붙임
  card(s, 0.6, 5.4, 12.13, 1.2, C.amber);
  s.addText("+  벽을 숫자로 새긴다", { x: 0.9, y: 5.58, w: 2.9, h: 0.4, fontFace: FONT, fontSize: 14, bold: true, color: C.ink, valign: "middle", margin: 0 });
  s.addText([
    { text: "각 칸에 재질의 전기적 성질(유전율 εr, 전도율 σ)을 기록하면, ", options: { color: C.body } },
    { text: "콘크리트·유리·석고보드가 전파를 서로 다르게 막고 통과시키는 현상", options: { bold: true, color: C.ink } },
    { text: "이 저절로 재현됩니다.", options: { color: C.body } },
  ], { x: 3.55, y: 5.58, w: 8.9, h: 0.85, fontFace: FONT, fontSize: 12.5, valign: "middle", margin: 0, lineSpacingMultiple: 1.06 });
  footer(s, 4);
})();

// ============================================================
// 5 — 직접 돌려본 결과 (실측 이미지)
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.light);
  header(s, "PART 1 · 실측", "직접 돌려본 결과 — 그림자와 회절이 보인다");

  // real image (978x726, aspect 1.347)
  const iw = 6.6, ih = iw / 1.347;
  const ix = 0.6, iy = 1.85;
  s.addShape(pres.shapes.RECTANGLE, { x: ix - 0.06, y: iy - 0.06, w: iw + 0.12, h: ih + 0.12, fill: { color: C.navy }, line: { color: C.line, width: 1 }, shadow: shadow() });
  s.addImage({ path: "fdtd_field.png", x: ix, y: iy, w: iw, h: ih });
  s.addText("실제 검증 엔진(FdtdWaveSimulator) 실시간 파동 시각화 · 도식이 아닌 계산 결과", {
    x: ix, y: iy + ih + 0.08, w: iw, h: 0.3, fontFace: FONT, fontSize: 10.5, color: C.muted, align: "center", margin: 0 });

  // right: read-the-picture guide
  const rx = 7.5, rw = 5.23;
  const items = [
    ["밝은 곳 = 강한 신호", "공유기(소스)에서 가까울수록 전파가 강합니다.", C.orange],
    ["벽 뒤 어두운 ‘그림자’", "콘크리트 벽이 전파를 막아 뒤쪽이 약해집니다.", C.teal],
    ["문틈으로 돌아 들어감 (회절)", "벽이 끊긴 틈으로 전파가 휘어 들어갑니다.", C.green],
    ["칸막이 뒤 약해진 신호", "석고보드는 약하게 막아 조금만 줄어듭니다.", C.amber],
  ];
  let yy = 2.0;
  items.forEach((it) => {
    s.addShape(pres.shapes.OVAL, { x: rx, y: yy + 0.05, w: 0.2, h: 0.2, fill: { color: it[2] } });
    s.addText(it[0], { x: rx + 0.35, y: yy - 0.05, w: rw - 0.4, h: 0.38, fontFace: FONT, fontSize: 14, bold: true, color: C.ink, valign: "middle", margin: 0 });
    s.addText(it[1], { x: rx + 0.35, y: yy + 0.33, w: rw - 0.4, h: 0.5, fontFace: FONT, fontSize: 12, color: C.body, valign: "top", margin: 0, lineSpacingMultiple: 1.03 });
    yy += 1.0;
  });
  s.addText("→ 거리 공식으로 ‘추정’한 게 아니라 물결을 실제로 퍼뜨려 얻은 결과라, 그림자·회절이 저절로 나타납니다.", {
    x: rx, y: yy + 0.05, w: rw, h: 0.7, fontFace: FONT, fontSize: 12, bold: true, color: C.teal, valign: "top", margin: 0, lineSpacingMultiple: 1.05 });
  footer(s, 5);
})();

// ============================================================
// 6 — 성능 (직접 측정)
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.light);
  header(s, "PART 1 · 실측", "성능 — 노트북에서 방 하나를 1~2초에");

  s.addText("아래 수치는 추정이 아니라 검증 엔진(FdtdWaveSimulator)을 직접 돌려 측정한 값입니다 (2.4GHz, 8m×6m 방, 격자 한 칸 약 1.5cm, 멀티스레드 CPU 7스레드).", {
    x: 0.6, y: 1.6, w: 12.1, h: 0.4, fontFace: FONT, fontSize: 13, color: C.body, margin: 0 });

  const stats = [
    ["≈2.8초", "방 하나 정밀 시뮬 1회", C.orange],
    ["232,171", "계산한 칸 수 (551×421)", C.teal],
    ["4,000", "시간 스텝", C.green],
    ["≈3.3억", "초당 칸 갱신 수", C.cyan],
  ];
  const sw = 2.93, sg = 0.13, y0 = 2.2, sh = 1.55;
  stats.forEach((st, i) => {
    const x = 0.6 + i * (sw + sg);
    card(s, x, y0, sw, sh, st[2]);
    s.addText(st[0], { x: x + 0.25, y: y0 + 0.22, w: sw - 0.5, h: 0.7, fontFace: FONT, fontSize: 30, bold: true, color: st[2], margin: 0 });
    s.addText(st[1], { x: x + 0.27, y: y0 + 0.98, w: sw - 0.5, h: 0.45, fontFace: FONT, fontSize: 11.5, color: C.body, valign: "top", margin: 0, lineSpacingMultiple: 1.0 });
  });

  // 비교 + 직관
  card(s, 0.6, 4.0, 6.0, 2.6, C.teal);
  s.addText("측정값 요약", { x: 0.85, y: 4.2, w: 5.5, h: 0.4, fontFace: FONT, fontSize: 15, bold: true, color: C.ink, margin: 0 });
  s.addTable([
    [{ text: "항목", options: tHead() }, { text: "값", options: tHead() }, { text: "비고", options: tHead() }],
    [{ text: "격자", options: tCell(true) }, { text: "551×421", options: tCell() }, { text: "≈23만 칸", options: tCell() }],
    [{ text: "1회 검증", options: tCell(true) }, { text: "≈2.8초", options: tCell() }, { text: "4000스텝", options: tCell() }],
  ], { x: 0.85, y: 4.7, w: 5.5, colW: [1.9, 1.9, 1.7], rowH: 0.42, border: { type: "solid", pt: 0.5, color: C.line }, align: "center", valign: "middle", fontFace: FONT });
  s.addText("주파수↑ → 파장↓ → 칸이 더 촘촘해야 함 → 계산량↑", { x: 0.85, y: 6.1, w: 5.5, h: 0.4, fontFace: FONT, fontSize: 11.5, color: C.muted, margin: 0 });

  card(s, 6.73, 4.0, 6.0, 2.6, C.orange);
  s.addText("그래서 설계가 중요하다", { x: 6.98, y: 4.2, w: 5.5, h: 0.4, fontFace: FONT, fontSize: 15, bold: true, color: C.ink, margin: 0 });
  s.addText([
    { text: "정밀하지만 1~2초가 드는 FDTD를 ", options: { color: C.body } },
    { text: "모든 후보 위치마다 돌리면 너무 느립니다.", options: { bold: true, color: C.ink } },
    { text: " 그래서 빠른 추정(DPM)으로 좋은 자리를 먼저 추리고, FDTD는 최종 검증에만 씁니다.", options: { color: C.body } },
  ], { x: 6.98, y: 4.7, w: 5.5, h: 1.3, fontFace: FONT, fontSize: 12.5, valign: "top", margin: 0, lineSpacingMultiple: 1.1 });
  s.addText("→ 다음 파트: 두 엔진의 협업", { x: 6.98, y: 6.1, w: 5.5, h: 0.4, fontFace: FONT, fontSize: 12, bold: true, color: C.orange, margin: 0 });
  footer(s, 6);
})();
function tHead() { return { fill: { color: "E7EFF4" }, color: C.ink, bold: true, fontSize: 11.5, fontFace: FONT }; }
function tCell(b) { return { color: b ? C.ink : C.body, bold: !!b, fontSize: 11.5, fontFace: FONT }; }

// ============================================================
// 7 — AP 추천 전체 흐름
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.light);
  header(s, "PART 2 · 자동 추천", "공유기 자리 추천 — 전체 흐름");
  s.addText("목표: 사용자가 고른 공간에서, 신호가 충분한 영역을 ‘가장 적은 공유기’로 최대한 덮는 자리 찾기. (목표 세기 −65dBm)", {
    x: 0.6, y: 1.6, w: 12.1, h: 0.4, fontFace: FONT, fontSize: 13, color: C.body, margin: 0 });

  const stages = [
    ["쓸 공간 고르기", "벽으로 둘러싼 방을\n클릭해 영역 지정", C.teal],
    ["후보 자리 만들기", "그 영역 안에만\n후보·측정점 배치", C.teal2],
    ["빠른 추정으로 선별", "DPM으로 ‘새로 덮는\n곳’이 가장 많은 자리 선택", C.amber],
    ["정밀 검증 + 보정", "FDTD로 재확인,\n부족하면 옮겨 재검증", C.orange],
    ["적용 → 전파 지도", "추천 자리에 공유기 놓고\n히트맵 즉시 생성", C.green],
  ];
  const pw = 2.3, gap = 0.18, x0 = 0.6, y0 = 2.25, ph = 2.65;
  stages.forEach((st, i) => {
    const x = x0 + i * (pw + gap);
    card(s, x, y0, pw, ph, st[2]);
    s.addShape(pres.shapes.OVAL, { x: x + pw/2 - 0.32, y: y0 + 0.28, w: 0.64, h: 0.64, fill: { color: st[2] } });
    s.addText(String(i + 1), { x: x + pw/2 - 0.32, y: y0 + 0.28, w: 0.64, h: 0.64, fontFace: FONT, fontSize: 19, bold: true, color: "FFFFFF", align: "center", valign: "middle", margin: 0 });
    s.addText(st[0], { x: x + 0.12, y: y0 + 1.05, w: pw - 0.24, h: 0.7, fontFace: FONT, fontSize: 13, bold: true, color: C.ink, align: "center", valign: "top", margin: 0 });
    s.addText(st[1], { x: x + 0.12, y: y0 + 1.62, w: pw - 0.24, h: 0.95, fontFace: FONT, fontSize: 10.8, color: C.body, align: "center", valign: "top", margin: 0, lineSpacingMultiple: 1.04 });
    if (i < stages.length - 1) s.addShape(pres.shapes.LINE, { x: x + pw + 0.01, y: y0 + 0.6, w: gap - 0.02, h: 0, line: { color: C.muted, width: 1.5, endArrowType: "triangle" } });
  });

  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 2.3, y: 5.35, w: 3.3, h: 0.55, rectRadius: 0.1, fill: { color: "FDE7D6" } });
  s.addText("③ 빠른 추정 = DPM", { x: 2.3, y: 5.35, w: 3.3, h: 0.55, fontFace: FONT, fontSize: 12.5, bold: true, color: C.orange, align: "center", valign: "middle", margin: 0 });
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 7.4, y: 5.35, w: 3.3, h: 0.55, rectRadius: 0.1, fill: { color: "FBE0D2" } });
  s.addText("④ 정밀 검증 = FDTD", { x: 7.4, y: 5.35, w: 3.3, h: 0.55, fontFace: FONT, fontSize: 12.5, bold: true, color: C.orange, align: "center", valign: "middle", margin: 0 });
  s.addText("이 두 단계(③④)가 어떻게 맞물리는지가 핵심입니다 — 다음 두 장에서 설명합니다.", {
    x: 0.6, y: 6.15, w: 12.1, h: 0.4, fontFace: FONT, fontSize: 12.5, bold: true, color: C.ink, align: "center", margin: 0 });
  footer(s, 7);
})();

// ============================================================
// 8 — DPM 쉽게 (빠른 추정)
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.light);
  header(s, "PART 2 · 빠른 추정", "DPM — ‘가장 잘 통하는 길’만 빠르게");

  // left: why fast / what
  card(s, 0.6, 1.75, 6.0, 4.9, C.amber);
  s.addText("왜 빠른 추정이 필요할까?", { x: 0.85, y: 1.95, w: 5.5, h: 0.4, fontFace: FONT, fontSize: 15, bold: true, color: C.ink, margin: 0 });
  s.addText("FDTD는 정확하지만 방 하나에 1~2초. 후보 자리가 수백 곳이면 다 돌리기엔 너무 오래 걸립니다.", {
    x: 0.85, y: 2.45, w: 5.5, h: 0.8, fontFace: FONT, fontSize: 12.5, color: C.body, valign: "top", margin: 0, lineSpacingMultiple: 1.08 });
  s.addText("DPM의 핵심 아이디어", { x: 0.85, y: 3.35, w: 5.5, h: 0.4, fontFace: FONT, fontSize: 15, bold: true, color: C.ink, margin: 0 });
  s.addText([
    { text: "물이 벽을 못 뚫으면 문으로 돌아 흐르듯, ", options: { color: C.body } },
    { text: "전파도 ‘가장 잘 통하는 길’을 따라간다", options: { bold: true, color: C.ink } },
    { text: "고 보고 — 그 한 경로의 손실만 빠르게 계산합니다. (지배경로 모델)", options: { color: C.body } },
  ], { x: 0.85, y: 3.85, w: 5.5, h: 1.2, fontFace: FONT, fontSize: 12.5, valign: "top", margin: 0, lineSpacingMultiple: 1.1 });
  s.addText([
    { text: "→ 1초에 수많은 후보를 평가", options: { bold: true, color: C.amber } },
    { text: " → 좋은 자리만 빠르게 추려냄", options: { color: C.body } },
  ], { x: 0.85, y: 5.5, w: 5.5, h: 0.8, fontFace: FONT, fontSize: 13, valign: "top", margin: 0, lineSpacingMultiple: 1.05 });

  // right: path schematic (직선 vs 우세경로)
  card(s, 6.73, 1.75, 6.0, 4.9, C.teal);
  s.addText("직선이 아니라 ‘돌아가는 길’", { x: 6.98, y: 1.95, w: 5.5, h: 0.4, fontFace: FONT, fontSize: 15, bold: true, color: C.ink, margin: 0 });
  const rx = 7.1, ry = 2.6, rw = 5.25, rh = 3.0;
  s.addShape(pres.shapes.RECTANGLE, { x: rx, y: ry, w: rw, h: rh, fill: { color: "EAF1F6" }, line: { color: C.muted, width: 1 } });
  s.addShape(pres.shapes.RECTANGLE, { x: rx + 2.7, y: ry, w: 0.13, h: 1.7, fill: { color: "9AAEBD" } }); // 내벽(문틈 아래)
  const apx = rx + 0.85, apy = ry + 2.3, tx = rx + 4.3, ty = ry + 0.7;
  s.addShape(pres.shapes.OVAL, { x: apx - 0.12, y: apy - 0.12, w: 0.24, h: 0.24, fill: { color: C.orange } });
  s.addText("공유기", { x: apx - 0.45, y: apy + 0.14, w: 0.9, h: 0.3, fontFace: FONT, fontSize: 10, bold: true, color: C.orange, align: "center", margin: 0 });
  s.addShape(pres.shapes.OVAL, { x: tx - 0.1, y: ty - 0.1, w: 0.2, h: 0.2, fill: { color: C.teal } });
  s.addText("측정 지점", { x: tx - 0.5, y: ty - 0.44, w: 1.1, h: 0.3, fontFace: FONT, fontSize: 10, bold: true, color: C.teal, align: "center", margin: 0 });
  const dx = rx + 2.76, dy = ry + 1.85;
  s.addShape(pres.shapes.LINE, { x: apx, y: apy, w: dx - apx, h: dy - apy, line: { color: C.amber, width: 2.5 } });
  s.addShape(pres.shapes.LINE, { x: dx, y: dy, w: tx - dx, h: ty - dy, line: { color: C.amber, width: 2.5 } });
  s.addShape(pres.shapes.LINE, { x: apx, y: apy, w: tx - apx, h: ty - apy, line: { color: C.red, width: 1, dashType: "dash", transparency: 25 } });
  s.addText([
    { text: "● ", options: { color: C.amber, bold: true } }, { text: "우세 경로(문으로 우회)   ", options: { color: C.body } },
    { text: "┄ ", options: { color: C.red } }, { text: "직선(벽에 막힘)", options: { color: C.body } },
  ], { x: rx, y: ry + rh + 0.12, w: rw, h: 0.35, fontFace: FONT, fontSize: 11, align: "center", margin: 0 });
  footer(s, 8);
})();

// ============================================================
// 9 — DPM ↔ FDTD 협업 (핵심)
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.light);
  header(s, "PART 2 · 핵심", "두 엔진의 협업 — 빠르게 찾고, 정밀하게 확인");

  s.addText("초안은 빠르게(DPM), 최종 확인은 정밀하게(FDTD). 건축에서 스케치로 빠르게 잡고 구조해석으로 검증하는 것과 같습니다.", {
    x: 0.6, y: 1.6, w: 12.1, h: 0.4, fontFace: FONT, fontSize: 13, color: C.body, margin: 0 });

  // Stage A
  card(s, 0.6, 2.2, 5.55, 3.1, C.amber);
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 0.85, y: 2.4, w: 3.4, h: 0.42, rectRadius: 0.21, fill: { color: C.amber } });
  s.addText("STEP A · 빠른 추정 (DPM)", { x: 0.85, y: 2.4, w: 3.4, h: 0.42, fontFace: FONT, fontSize: 11.5, bold: true, color: "FFFFFF", align: "center", valign: "middle", margin: 0 });
  s.addText([
    { text: "수백 개 후보 자리를 1초 안에 채점", options: { bullet: { code: "2022" }, color: C.body, breakLine: true, paraSpaceAfter: 7 } },
    { text: "‘새로 덮이는 측정점’이 가장 많은 자리를 하나씩 선택 (그리디)", options: { bullet: { code: "2022" }, color: C.body, breakLine: true, paraSpaceAfter: 7 } },
    { text: "여러 대를 놓을 땐 서로 너무 붙지 않게 분산", options: { bullet: { code: "2022" }, color: C.body } },
  ], { x: 0.95, y: 3.0, w: 5.05, h: 2.1, fontFace: FONT, fontSize: 12.5, valign: "top", margin: 0, lineSpacingMultiple: 1.05 });

  // arrow
  s.addShape(pres.shapes.LINE, { x: 6.25, y: 3.75, w: 0.85, h: 0, line: { color: C.ink, width: 2.5, endArrowType: "triangle" } });
  s.addText("추천 자리", { x: 6.05, y: 3.35, w: 1.25, h: 0.3, fontFace: FONT, fontSize: 10, bold: true, color: C.ink, align: "center", margin: 0 });

  // Stage B
  card(s, 7.18, 2.2, 5.55, 3.1, C.orange);
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 7.43, y: 2.4, w: 3.4, h: 0.42, rectRadius: 0.21, fill: { color: C.orange } });
  s.addText("STEP B · 정밀 검증 (FDTD)", { x: 7.43, y: 2.4, w: 3.4, h: 0.42, fontFace: FONT, fontSize: 11.5, bold: true, color: "FFFFFF", align: "center", valign: "middle", margin: 0 });
  s.addText([
    { text: "추천된 자리에 공유기를 놓고 실제 파동 시뮬 실행", options: { bullet: { code: "2022" }, color: C.body, breakLine: true, paraSpaceAfter: 7 } },
    { text: "진짜로 신호가 충분히 닿는지, 고르게 퍼지는지 측정", options: { bullet: { code: "2022" }, color: C.body, breakLine: true, paraSpaceAfter: 7 } },
    { text: "DPM 추정이 놓친 그림자·회절을 여기서 잡아냄", options: { bullet: { code: "2022" }, color: C.body } },
  ], { x: 7.53, y: 3.0, w: 5.05, h: 2.1, fontFace: FONT, fontSize: 12.5, valign: "top", margin: 0, lineSpacingMultiple: 1.05 });

  // feedback loop band
  card(s, 0.6, 5.5, 12.13, 1.1, C.teal);
  s.addText("부족하면? 되먹임 보정", { x: 0.9, y: 5.62, w: 3.3, h: 0.4, fontFace: FONT, fontSize: 14, bold: true, color: C.ink, valign: "middle", margin: 0 });
  s.addText([
    { text: "검증에서 커버가 부족하면 → 신호 약한 곳 방향으로 공유기를 옮기고 → B를 다시 검증 (최대 3회). ", options: { color: C.body } },
    { text: "이렇게 빠름(DPM)으로 좁히고 정확함(FDTD)으로 마무리해, 속도와 정확도를 모두 얻습니다.", options: { bold: true, color: C.ink } },
  ], { x: 4.0, y: 5.55, w: 8.5, h: 0.95, fontFace: FONT, fontSize: 12, valign: "middle", margin: 0, lineSpacingMultiple: 1.05 });
  footer(s, 9);
})();

// ============================================================
// 10 — UI/UX 워크플로우
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.light);
  header(s, "PART 3 · UI/UX", "비전문가도 따라오는 단계 안내");
  s.addText("‘무엇을 먼저 해야 하나’를 프로그램이 한 번에 하나씩 안내합니다.", {
    x: 0.6, y: 1.6, w: 12.1, h: 0.4, fontFace: FONT, fontSize: 13, color: C.body, margin: 0 });

  const flow = [
    ["도면 업로드", "평면도 이미지 열기", C.teal],
    ["크기 맞추기", "실제 거리로 보정", C.teal2],
    ["벽 그리기 완료", "버튼 클릭", C.amber],
    ["공유기 선택", "통신사 프리셋", C.orange],
    ["자리 추천", "공간 고르고 실행", C.orange],
    ["자동 전파지도", "적용 즉시 생성", C.green],
  ];
  const pw = 1.92, gap = 0.12, x0 = 0.6, y0 = 2.3, ph = 1.75;
  flow.forEach((f, i) => {
    const x = x0 + i * (pw + gap);
    card(s, x, y0, pw, ph, f[2]);
    s.addShape(pres.shapes.OVAL, { x: x + pw/2 - 0.28, y: y0 + 0.25, w: 0.56, h: 0.56, fill: { color: f[2] } });
    s.addText(String(i + 1), { x: x + pw/2 - 0.28, y: y0 + 0.25, w: 0.56, h: 0.56, fontFace: FONT, fontSize: 17, bold: true, color: "FFFFFF", align: "center", valign: "middle", margin: 0 });
    s.addText(f[0], { x: x + 0.1, y: y0 + 0.9, w: pw - 0.2, h: 0.4, fontFace: FONT, fontSize: 12, bold: true, color: C.ink, align: "center", margin: 0 });
    s.addText(f[1], { x: x + 0.1, y: y0 + 1.27, w: pw - 0.2, h: 0.42, fontFace: FONT, fontSize: 9.8, color: C.body, align: "center", valign: "top", margin: 0 });
    if (i < flow.length - 1) s.addShape(pres.shapes.LINE, { x: x + pw, y: y0 + 0.53, w: gap, h: 0, line: { color: C.muted, width: 1.3, endArrowType: "triangle" } });
  });

  const yb = 4.5;
  card(s, 0.6, yb, 5.95, 2.1, C.red);
  s.addText("기존 흐름의 문제", { x: 0.85, y: yb + 0.2, w: 5.5, h: 0.4, fontFace: FONT, fontSize: 14, bold: true, color: C.ink, margin: 0 });
  s.addText([
    { text: "공유기를 처음부터 사용자가 수동 배치", options: { bullet: { code: "2022" }, color: C.body, breakLine: true, paraSpaceAfter: 6 } },
    { text: "어디에 와이파이가 필요한지 모른 채 결과 표시", options: { bullet: { code: "2022" }, color: C.body, breakLine: true, paraSpaceAfter: 6 } },
    { text: "다음에 뭘 해야 할지 알기 어려움", options: { bullet: { code: "2022" }, color: C.body } },
  ], { x: 0.95, y: yb + 0.65, w: 5.4, h: 1.3, fontFace: FONT, fontSize: 11.8, valign: "top", margin: 0 });

  card(s, 6.78, yb, 5.95, 2.1, C.green);
  s.addText("개선된 흐름", { x: 7.03, y: yb + 0.2, w: 5.5, h: 0.4, fontFace: FONT, fontSize: 14, bold: true, color: C.ink, margin: 0 });
  s.addText([
    { text: "자동 추천이 수동 배치를 대체 (수정은 사후에만)", options: { bullet: { code: "2022" }, color: C.body, breakLine: true, paraSpaceAfter: 6 } },
    { text: "‘쓸 공간’을 먼저 고르고 그 영역만 측정", options: { bullet: { code: "2022" }, color: C.body, breakLine: true, paraSpaceAfter: 6 } },
    { text: "버튼·안내로 다음 단계를 명확히 제시", options: { bullet: { code: "2022" }, color: C.body } },
  ], { x: 7.13, y: yb + 0.65, w: 5.4, h: 1.3, fontFace: FONT, fontSize: 11.8, valign: "top", margin: 0 });
  footer(s, 10);
})();

// ============================================================
// 11 — 마무리
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.navy);
  const cx = 1.2, cy = 6.6;
  [2.4, 1.7, 1.0].forEach((r, i) => s.addShape(pres.shapes.OVAL, { x: cx - r, y: cy - r, w: r * 2, h: r * 2, fill: { type: "solid", color: C.navy, transparency: 100 }, line: { color: i % 2 ? C.teal : C.cyan, width: 1, transparency: 55 } }));

  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 0.85, y: 0.7, w: 2.4, h: 0.4, rectRadius: 0.2, fill: { color: C.navy2 }, line: { color: C.teal, width: 1 } });
  s.addText("SUMMARY", { x: 0.85, y: 0.7, w: 2.4, h: 0.4, fontFace: FONT, fontSize: 11, bold: true, color: C.cyan, align: "center", valign: "middle", charSpacing: 1, margin: 0 });
  s.addText("정리 — 무엇을 어떻게 만들었나", { x: 0.82, y: 1.2, w: 11, h: 0.7, fontFace: FONT, fontSize: 28, bold: true, color: "FFFFFF", margin: 0 });

  const done = [
    ["FDTD 구현", "공간을 칸으로 나눠 전파를 물결처럼 한 칸씩 퍼뜨려 계산. 그림자·회절까지 자연 재현", C.cyan],
    ["실측 검증", "검증 엔진을 직접 돌려 실제 전파장 이미지 확보 + 성능 측정 (방 하나 ≈2.8초, 초당 약 3.3억 칸)", C.amber],
    ["DPM ↔ FDTD", "빠른 추정으로 자리를 찾고, 정밀 시뮬로 검증·보정 — 속도와 정확도를 모두 확보", C.green],
  ];
  const cw = 3.85, cg = 0.2, y0 = 2.3, ch = 2.3;
  done.forEach((d, i) => {
    const x = 0.85 + i * (cw + cg);
    s.addShape(pres.shapes.RECTANGLE, { x, y: y0, w: cw, h: ch, fill: { color: C.navy2 }, line: { color: "1E3A52", width: 1 } });
    s.addShape(pres.shapes.RECTANGLE, { x, y: y0, w: cw, h: 0.09, fill: { color: d[2] } });
    s.addText(d[0], { x: x + 0.3, y: y0 + 0.3, w: cw - 0.6, h: 0.5, fontFace: FONT, fontSize: 17, bold: true, color: d[2], margin: 0 });
    s.addText(d[1], { x: x + 0.3, y: y0 + 0.95, w: cw - 0.6, h: 1.2, fontFace: FONT, fontSize: 12.5, color: "C7D6E2", valign: "top", margin: 0, lineSpacingMultiple: 1.12 });
  });

  s.addText("다음 단계", { x: 0.85, y: 5.0, w: 4, h: 0.4, fontFace: FONT, fontSize: 15, bold: true, color: "FFFFFF", margin: 0 });
  s.addText([
    { text: "메인 화면에서 사용 공간을 직접 편집 (추천 때 쓴 영역 선택 재사용)", options: { bullet: { code: "2022" }, color: "AFC3D4", breakLine: true, paraSpaceAfter: 6 } },
    { text: "FDTD 검증 단계 속도 최적화 · 여러 주파수 동시 평가", options: { bullet: { code: "2022" }, color: "AFC3D4" } },
  ], { x: 0.95, y: 5.45, w: 11.5, h: 1.1, fontFace: FONT, fontSize: 13, valign: "top", margin: 0 });
  footer(s, 11);
})();

pres.writeFile({ fileName: "Capstone_WiFi_Heatmap.pptx" }).then(f => console.log("written:", f));
