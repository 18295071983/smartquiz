//COLORES
const red = document.querySelector('.red');
const blue = document.querySelector('.blue');
const green = document.querySelector('.green');
const pink = document.querySelector('.pink');
const yellow = document.querySelector('.yellow');
const purple = document.querySelector('.purple');
const orange = document.querySelector('.orange');

const redCode = "#FF0000";
const blueCode = "#0049FF";
const greenCode = "#4caf50";
const pinkode = "#E400FF";
const yellowCode = "#FFFB00";
const purplecode = "#4D00FF";
const orangeCode = "#FF9700";

const element = document.querySelector("#canvas");
const ctx = element.getContext("2d");

var w = document.body.clientWidth;
var h = document.body.clientHeight;
var color = "green";

element.width = w;
element.height = h;

var position = Array(300).join(0).split("");

const initMatrix = () => 
{
    ctx.fillStyle = "rgba(0,15,2,0.15)";
    ctx.fillRect(0,0, w, h);
    ctx.fillStyle = color;
    ctx.font = "11pt";

    position.map
    (
        (y, index) => 
        {
            const text = String.fromCharCode(1e5 + Math.random() * 30);
            const x = (index * 15);
            
            canvas.getContext("2d").fillText(text,x,y);

            y > 100 + Math.random() * 1e5 
                ? (position[index] = 0)
                : (position[index] = y + 15);
        }
    );
};

setInterval(initMatrix, 90);

red.addEventListener('click', function(){ color = redCode; });
blue.addEventListener('click', function(){ color = blueCode; });
green.addEventListener('click', function(){ color = greenCode; });
pink.addEventListener('click', function(){ color = pinkode; });
yellow.addEventListener('click', function(){ color = yellowCode; });
purple.addEventListener('click', function(){ color = purplecode; });
orange.addEventListener('click', function(){ color = orangeCode; });