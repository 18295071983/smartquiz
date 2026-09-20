/**
 * @type {HTMLCanvasElement | null}
 */
let canvas = null;
/**
 * @type {CanvasRenderingContext2D | null}
 */
let ctx = null;
let lastDate = null;

const play = () => {
  if (!canvas) return;
  if (!ctx) {
    ctx = canvas.getContext("2d");
  }
  // Draw the canvas
  draw();
  // Start the game loop
  requestAnimationFrame(play);
};

/**
 * Gets the center of the canvas
 * @returns {{x: number, y: number}}
 */
const getCenter = () => {
  return { x: canvas.width / 2, y: canvas.height / 2 };
};

const relative = (x) => {
  return canvas.width / 400 * x;
}

const draw = () => {
  if (!ctx) return;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle"

  const now = new Date();

  // If the seconds are the same, don't draw
  if (lastDate && lastDate.getSeconds() === now.getSeconds()) {
    return;
  }
  lastDate = now;

  // Clear the canvas
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  
  const center = getCenter();
  // Draw marks
  drawMarks();
  // Draw circle at center
  drawCircle(center, 5);
  const centerOffset = -4;
  // Draw second hand
  drawHand(centerOffset, 12, "Arial Light", now.getSeconds(), now.getSeconds() / 60, 10, 4);
  // Draw minute hand
  drawHand(centerOffset, 18, "Arial", now.getMinutes(), now.getMinutes() / 60, 7, 4);
  // Draw hour hand
  drawHand(centerOffset, 18, "Arial Black", now.getHours(), now.getHours() % 12 / 12, 5, 0);
};

const drawMarks = () => {
  const center = getCenter();
  const radius = relative(200);
  for (let i = 0; i < 60; i++) {
    const isHour = i % 5 == 0;
    const width = isHour ? 3 : 2;
    const length = relative(isHour ? 170 : 190);

    const angle = Math.PI * 2 * i / 60 - Math.PI / 2;
    const angleCosine = Math.cos(angle);
    const angleSine = Math.sin(angle);

    const start = {
      x: center.x + angleCosine * radius,
      y: center.y + angleSine * radius
    };

    const end = {
      x: center.x + angleCosine * length,
      y: center.y + angleSine * length
    };

    ctx.lineWidth = relative(width);
    drawLine(start, end);
  }
}

const drawLine = (start, end) => {
  ctx.beginPath();
  ctx.moveTo(start.x, start.y);
  ctx.lineTo(end.x, end.y);
  ctx.stroke();
}

const drawCircle = (center, radius) => {
  ctx.beginPath();
  ctx.arc(center.x, center.y, relative(radius), 0, Math.PI * 2);
  ctx.fill();
}

const drawHand = (fromCenterDistance, fontSize, font, value, percentage, sequenceLength, spacing) => {
  // Set the font
  ctx.font = `${relative(fontSize)}px ${font}`;
  // Calculate the angle
  const angleInRadians = Math.PI * 2 * percentage - Math.PI / 2;
  // Calculate the cosine and sine of the angle
  const angleCosine = Math.cos(angleInRadians);
  const angleSine = Math.sin(angleInRadians);
  // Calculate the start position
  const center = getCenter();
  const radiusFromCenter = relative(fromCenterDistance);
  let start = { 
    x: center.x + angleCosine * radiusFromCenter,
    y: center.y + angleSine * radiusFromCenter
  };
  // Draw the text and calculate the next positions
  for (i = 0; i < sequenceLength; i++) {
    start.x += angleCosine * relative(fontSize + spacing);
    start.y += angleSine * relative(fontSize + spacing);
    ctx.fillText(value, start.x, start.y);
  }
}

// Invoked when the DOM is fully loaded
const onLoad = () => {
  canvas = document.querySelector("canvas");
  onResize(); // Set the initial size
  play(); // Start the game loop
};

const onResize = () => {
  if (!canvas) return;
  
  const { innerWidth, innerHeight } = window;
  const size = Math.min(innerWidth, innerHeight);
  const scale = 0.9;
  
  canvas.width = size * scale;
  canvas.height  = size * scale;

  lastDate = null; // Force redraw
}

// Assign the listeners
window.addEventListener("DOMContentLoaded", onLoad);
window.addEventListener("resize", onResize);