/* 추가 슬라이드 2장 — 기존 캡스톤 덱과 동일 스타일
 *  A) AP 자동추천 — DPM이 자리를 고르는 법
 *  B) 와이파이 쓸 공간만 골라낸다 (Flood Fill)
 * Run: NODE_PATH=/opt/homebrew/lib/node_modules node build_extra.js
 */
const pptxgen = require("pptxgenjs");
const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE";
pres.author = "Wi-Fi Heatmap Simulator Team";
pres.title  = "추가 슬라이드 — AP 추천 / Flood Fill";

const W = 13.33, H = 7.5;
const C = {
  navy:"0C1B2A", navy2:"12273B", ink:"13283B", body:"33475B", muted:"6B7C8F",
  light:"F3F7FA", card:"FFFFFF", line:"DCE6EE",
  teal:"1C7293", teal2:"14A0B0", cyan:"38BDF8", orange:"F2792B", amber:"F4B23E",
  green:"3FA86A", red:"D9534F",
};
const FONT = "AppleGothic";
const shadow = () => ({ type:"outer", color:"0C1B2A", blur:9, offset:3, angle:90, opacity:0.18 });
function bg(s,c){ s.background={color:c}; }
function header(s, part, title){
  s.addShape(pres.shapes.ROUNDED_RECTANGLE,{x:0.6,y:0.45,w:2.4,h:0.34,rectRadius:0.17,fill:{color:C.teal}});
  s.addText(part,{x:0.6,y:0.45,w:2.4,h:0.34,fontFace:FONT,fontSize:10.5,bold:true,color:"FFFFFF",align:"center",valign:"middle",charSpacing:1,margin:0});
  s.addText(title,{x:0.58,y:0.9,w:12.1,h:0.7,fontFace:FONT,fontSize:26,bold:true,color:C.ink,align:"left",valign:"middle",margin:0});
}
function card(s,x,y,w,h,bar){
  s.addShape(pres.shapes.RECTANGLE,{x,y,w,h,fill:{color:C.card},line:{color:C.line,width:1},shadow:shadow()});
  if(bar) s.addShape(pres.shapes.RECTANGLE,{x,y,w:0.09,h,fill:{color:bar}});
}
function footer(s,n){
  s.addText("Wi-Fi Heatmap Simulator · 캡스톤 디자인",{x:0.6,y:H-0.42,w:8,h:0.3,fontFace:FONT,fontSize:9,color:C.muted,align:"left",margin:0});
  s.addText(n,{x:W-1.4,y:H-0.42,w:0.8,h:0.3,fontFace:FONT,fontSize:9,color:C.muted,align:"right",margin:0});
}

// ============================================================
// A — AP 자동추천: DPM이 자리를 고르는 법
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.light);
  header(s, "PART 2 · AP 추천", "좋은 자리를 고르는 기준 — DPM 채점");

  // 왼쪽: 4단계
  card(s, 0.6, 1.8, 6.05, 4.85, C.amber);
  s.addText("후보를 점수 매겨 한 대씩 선택", { x:0.85, y:2.0, w:5.5, h:0.4, fontFace:FONT, fontSize:15, bold:true, color:C.ink, margin:0 });
  const steps = [
    ["후보 격자 만들기", "쓸 공간 안에 후보 위치를 촘촘히 배치", C.teal],
    ["각 후보 채점", "후보마다 지배경로(Dijkstra)로 모든 측정점까지 신호 세기 계산", C.teal2],
    ["점수 비교 → 선택", "‘새로 덮는 측정점’이 가장 많은 후보를 고름", C.amber],
    ["그리디 반복", "한 대씩 추가하며 아직 안 닿는 곳을 메움", C.orange],
  ];
  let yy = 2.55;
  steps.forEach((st,i)=>{
    s.addShape(pres.shapes.OVAL,{x:0.85,y:yy,w:0.42,h:0.42,fill:{color:st[2]}});
    s.addText(String(i+1),{x:0.85,y:yy,w:0.42,h:0.42,fontFace:FONT,fontSize:14,bold:true,color:"FFFFFF",align:"center",valign:"middle",margin:0});
    s.addText(st[0],{x:1.4,y:yy-0.04,w:5.0,h:0.38,fontFace:FONT,fontSize:14,bold:true,color:C.ink,valign:"middle",margin:0});
    s.addText(st[1],{x:1.4,y:yy+0.36,w:5.05,h:0.55,fontFace:FONT,fontSize:11.8,color:C.body,valign:"top",margin:0,lineSpacingMultiple:1.04});
    yy += 1.0;
  });

  // 오른쪽: 점수 함수 박스 + 설명
  card(s, 6.73, 1.8, 6.0, 4.85, C.orange);
  s.addText("점수 함수 — 세 가지를 한 점수로", { x:6.98, y:2.0, w:5.5, h:0.4, fontFace:FONT, fontSize:15, bold:true, color:C.ink, margin:0 });
  s.addShape(pres.shapes.RECTANGLE,{x:6.98,y:2.55,w:5.5,h:1.5,fill:{color:"0E2233"}});
  s.addText([
    {text:"점수 = 새로 덮는 측정점 × 1000", options:{color:C.amber, breakLine:true}},
    {text:"     + 최약 신호 보너스   × 10", options:{color:C.cyan, breakLine:true}},
    {text:"     + 신호 균일도 보너스 × 5", options:{color:C.green, breakLine:true}},
    {text:"     + 평균 신호 세기", options:{color:"9FB6C7"}},
  ], { x:7.18, y:2.62, w:5.15, h:1.36, fontFace:"Consolas", fontSize:12.5, valign:"middle", margin:0, lineSpacingMultiple:1.12 });

  const why = [
    ["덮는 양 (최우선)", "신호가 닿는 새 지점이 가장 많은 곳을 우선", C.amber],
    ["약한 곳 보강", "가장 약한 지점의 신호를 끌어올리는 자리에 가산", C.cyan],
    ["고르게", "신호 편차가 작아 균일한 자리에 가산", C.green],
  ];
  let ry = 4.3;
  why.forEach((w0)=>{
    s.addShape(pres.shapes.OVAL,{x:6.98,y:ry+0.04,w:0.2,h:0.2,fill:{color:w0[2]}});
    s.addText(w0[0],{x:7.3,y:ry-0.06,w:5.2,h:0.34,fontFace:FONT,fontSize:13,bold:true,color:C.ink,valign:"middle",margin:0});
    s.addText(w0[1],{x:7.3,y:ry+0.28,w:5.2,h:0.34,fontFace:FONT,fontSize:11.3,color:C.body,valign:"top",margin:0});
    ry += 0.72;
  });

  footer(s, "A");
})();

// ============================================================
// B — 와이파이 쓸 공간만 골라낸다 (Flood Fill)
// ============================================================
(() => {
  const s = pres.addSlide(); bg(s, C.light);
  header(s, "PART 2 · 사용 공간", "와이파이 쓸 공간만 골라낸다 (Flood Fill)");
  s.addText("클릭 한 번으로 방 내부만 자동 인식 → 그 영역만 추천·측정에 사용", {
    x:0.6, y:1.6, w:12.1, h:0.4, fontFace:FONT, fontSize:13, color:C.body, margin:0 });

  // 왼쪽: 3단계
  card(s, 0.6, 2.2, 6.0, 3.0, C.teal);
  const steps = [
    ["벽을 격자에 새김", "그린 벽을 칸 단위로 표시(래스터화)", C.teal],
    ["클릭 지점에서 번지기", "벽에 막힐 때까지 방 안쪽을 자동으로 채움 (BFS)", C.teal2],
    ["그 영역만 사용", "후보 배치·신호 측정·품질 평가를 모두 이 영역으로 한정", C.amber],
  ];
  let yy = 2.45;
  steps.forEach((st,i)=>{
    s.addShape(pres.shapes.OVAL,{x:0.85,y:yy,w:0.42,h:0.42,fill:{color:st[2]}});
    s.addText(String(i+1),{x:0.85,y:yy,w:0.42,h:0.42,fontFace:FONT,fontSize:14,bold:true,color:"FFFFFF",align:"center",valign:"middle",margin:0});
    s.addText(st[0],{x:1.4,y:yy-0.04,w:5.0,h:0.38,fontFace:FONT,fontSize:14,bold:true,color:C.ink,valign:"middle",margin:0});
    s.addText(st[1],{x:1.4,y:yy+0.36,w:5.0,h:0.55,fontFace:FONT,fontSize:11.8,color:C.body,valign:"top",margin:0,lineSpacingMultiple:1.04});
    yy += 0.92;
  });

  // 오른쪽: 도식 — 방 + 벽 + 클릭점에서 채워진 영역
  card(s, 6.73, 2.2, 6.0, 3.0, C.cyan);
  s.addText("클릭 → 벽에 막힐 때까지 번짐", { x:6.98, y:2.4, w:5.5, h:0.35, fontFace:FONT, fontSize:13.5, bold:true, color:C.ink, margin:0 });
  const rx=7.55, ry=2.95, rw=4.4, rh=2.0;
  // 채워진 사용 공간(연한 청록)
  s.addShape(pres.shapes.RECTANGLE,{x:rx,y:ry,w:rw*0.62,h:rh,fill:{color:"D5EEF3"},line:{color:C.teal2,width:1}});
  // 바깥(미선택) 영역
  s.addShape(pres.shapes.RECTANGLE,{x:rx+rw*0.62,y:ry,w:rw*0.38,h:rh,fill:{color:"F0F0F0"},line:{color:C.line,width:1}});
  // 내벽(문틈 있음)
  s.addShape(pres.shapes.RECTANGLE,{x:rx+rw*0.62-0.05,y:ry,w:0.1,h:rh*0.6,fill:{color:"9AAEBD"}});
  // 클릭 지점
  s.addShape(pres.shapes.OVAL,{x:rx+rw*0.28,y:ry+rh*0.45,w:0.22,h:0.22,fill:{color:C.orange}});
  s.addText("클릭", {x:rx+rw*0.28-0.35,y:ry+rh*0.45+0.2,w:0.9,h:0.28,fontFace:FONT,fontSize:9.5,bold:true,color:C.orange,align:"center",margin:0});
  s.addText("사용 공간", {x:rx+0.2,y:ry+0.12,w:1.6,h:0.3,fontFace:FONT,fontSize:11,bold:true,color:C.teal,margin:0});
  s.addText("제외", {x:rx+rw*0.66,y:ry+0.12,w:1.2,h:0.3,fontFace:FONT,fontSize:10.5,color:C.muted,margin:0});
  s.addText("벽이 영역을 가두고, 문틈은 같은 방으로 이어줌", { x:6.98, y:5.05, w:5.5, h:0.3, fontFace:FONT, fontSize:10.5, color:C.muted, margin:0 });

  // 하단: Before/After 의미
  card(s, 0.6, 5.4, 5.95, 1.2, C.red);
  s.addText("공간 지정 없이", { x:0.85, y:5.55, w:5.4, h:0.35, fontFace:FONT, fontSize:13, bold:true, color:C.ink, margin:0 });
  s.addText("베란다·복도까지 평가에 포함 → 커버율·품질이 왜곡됨", { x:0.85, y:5.92, w:5.45, h:0.6, fontFace:FONT, fontSize:11.8, color:C.body, valign:"top", margin:0, lineSpacingMultiple:1.05 });
  card(s, 6.78, 5.4, 5.95, 1.2, C.green);
  s.addText("사용 공간 지정", { x:7.03, y:5.55, w:5.4, h:0.35, fontFace:FONT, fontSize:13, bold:true, color:C.ink, margin:0 });
  s.addText("실제 생활 공간만 평가 → 의미 있는 결과 (추천도 같은 영역 기준)", { x:7.03, y:5.92, w:5.45, h:0.6, fontFace:FONT, fontSize:11.8, color:C.body, valign:"top", margin:0, lineSpacingMultiple:1.05 });

  footer(s, "B");
})();

pres.writeFile({ fileName: "Capstone_추가슬라이드.pptx" }).then(f => console.log("written:", f));
