const reloj = document.querySelector(".reloj");
const hr = document.querySelector('#hr');
const mn = document.querySelector('#mn');
const sc = document.querySelector('#sc');
const toggle = document.querySelector('.toggle');

const deg = 6;
var horaGlobal = 0;

const getHora = () => 
{
    const digital = document.querySelector('#digital');
    let digitalStyle = window.getComputedStyle(digital);
    let displayValue = digitalStyle.getPropertyValue('display');

    let fecha = new Date();

    if(displayValue == 'none')
    {
        let hh = fecha.getHours() * 30;
        let mm = fecha.getMinutes() * deg;
        let ss = fecha.getSeconds() * deg;
        
        //ROTAR RELOJ ANALOGO
        hr.style.transform = `rotateZ(${hh+(mm/12)}deg)`;
        mn.style.transform = `rotateZ(${(mm)}deg)`;
        sc.style.transform = `rotateZ(${(ss)}deg)`;

        horaGlobal = fecha.getHours();
    }
    else
    {
        //METER HORA EN CONST TIEMPO
        const tiempo = 
        {
            hora: fecha.getHours(),
            minuto: fecha.getMinutes(),
            segundo: fecha.getSeconds(),
        };

        //PONER CEROS IZQUIERDA SI ES NECESARIO
        if(`${tiempo.minuto}` < 10)
        tiempo.minuto = 0 + `${tiempo.minuto}`;
        if(`${tiempo.hora}` < 10)
        tiempo.hora = 0 + `${tiempo.hora}`;
        
        //CAMBIAR TEXTO DE HTML
        reloj.innerHTML = `${tiempo.hora} : ${tiempo.minuto}`;
        //GUARDAR HORA
        horaGlobal = `${tiempo.hora}`;
    }

    //reloj.innerHTML = `${tiempo.hora} : ${tiempo.minuto} : ${tiempo.segundo}`;
};

const changeSaludo = () =>
{
    const saludo = document.querySelector(".saludo");

    if(horaGlobal >= 0 && horaGlobal < 7)
        saludo.innerHTML = "Buenas Noches, Jesús";
    else if(horaGlobal >= 7 && horaGlobal < 12)
        saludo.innerHTML = "Buenos Dias, Jesús";
    else if(horaGlobal >= 12 && horaGlobal < 19)
        saludo.innerHTML = "Buenas Tardes, Jesús";
    else if(horaGlobal >= 19)
        saludo.innerHTML = "Buenas Noches, Jesús";
}

function changeClock()
{
    const ana = document.querySelector('#analogo');
    const dig = document.querySelector('#digital');

    //DIGITAL
    if(toggle.checked)
    {
        ana.style.animationName = 'bounceOutRight';
        dig.style.animationName = 'bounceInLeft';

        setTimeout(() => {
            ana.style.display = 'none';
            dig.style.display = 'block';
        }, 800);
        
        getHora();
    }
    //ANALOG
    else{
        dig.style.animationName = 'bounceOutRight';
        ana.style.animationName = 'bounceInLeft';
 
        setTimeout(() => {
            dig.style.display = 'none';
            ana.style.display = 'block';
         }, 800);
    }
}

window.onload = function()
{
    getHora();
    changeSaludo();
};

setInterval(getHora, 1000);
setInterval(changeSaludo, 3600000);
toggle.addEventListener('click', changeClock);