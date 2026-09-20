function Cube(el){
  this.el = el;

  this.setFront = v => el.children[0].textContent = v < 10 ? "0" + v : v;
  this.setUpper = v => el.children[1].childNodes[0].nodeValue = v < 10 ? "0" + v : v;

  this.rotate = () => {
    el.children[1].children[0].classList.add("transparent");
    el.classList.add("rotate");
  };

  this.reset = () => {
    el.children[1].children[0].classList.remove("transparent");
    el.classList.remove("rotate");
  };
}

const hour = new Cube(document.getElementById("hour-cube"));
const minute = new Cube(document.getElementById("minute-cube"));
const second = new Cube(document.getElementById("second-cube"));

let flip = false, prevH, prevM;

setInterval(() => {
  const d = new Date();
  const h = d.getHours();
  const m = d.getMinutes();
  const s = d.getSeconds();

  if(!flip){
    second.setUpper(s);
    second.setFront(s ? s - 1 : 59);
    second.rotate();

    if(m !== prevM){
      minute.setUpper(m);
      minute.setFront(m ? m - 1 : 59);
      minute.rotate();
    }

    if(h !== prevH){
      hour.setUpper(h);
      hour.setFront(h ? h - 1 : 23);
      hour.rotate();
    }
  } else {
    second.setFront(s);
    second.reset();

    if(m !== prevM){ minute.setFront(m); minute.reset(); }
    if(h !== prevH){ hour.setFront(h); hour.reset(); }

    prevH = h;
    prevM = m;
  }

  flip = !flip;
}, 500);
