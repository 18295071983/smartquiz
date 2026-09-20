document.addEventListener('DOMContentLoaded', () => {
  const hourColumn = document.getElementById('hours');
  const minuteColumn = document.getElementById('minutes');
  const secondColumn = document.getElementById('seconds');
  const digitalClock = document.getElementById('digital-clock');
  const geminiButton = document.getElementById('gemini-button');
  const factContainer = document.getElementById('fact-container');

  const BITS = 6;
  let currentTime = { h: 0, m: 0 };

  function createLights(columnElement) {
    for (let i = 0; i < BITS; i++) {
      const light = document.createElement('div');
      light.classList.add('light', 'w-8', 'h-8', 'md:w-12', 'md:h-12', 'rounded-full', 'bg-gray-700', 'transition', 'duration-200');
      columnElement.appendChild(light);
    }
  }

  function updateColumn(columnElement, onClass, number) {
    const binaryString = number.toString(2).padStart(BITS, '0');
    const lights = columnElement.children;
    for (let i = 0; i < BITS; i++) {
      const light = lights[i];
      const bit = binaryString[BITS - 1 - i];
      light.classList.toggle(onClass, bit === '1');
    }
  }

  function updateClock() {
    const now = new Date();
    const h = now.getHours();
    const m = now.getMinutes();
    const s = now.getSeconds();
    currentTime = { h, m };

    updateColumn(hourColumn, 'hour-on', h);
    updateColumn(minuteColumn, 'minute-on', m);
    updateColumn(secondColumn, 'second-on', s);

    digitalClock.textContent = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  async function getTimeFact() {
  geminiButton.disabled = true;
  geminiButton.textContent = 'Thinking...';
  factContainer.innerHTML = '<div class="animate-pulse">Fetching a fact from the cosmos...</div>';

  const { h, m } = currentTime;
  const timeString = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;

  // Sample static fun facts per hour
  const timeFacts = {
    "00": "Midnight marks the beginning of a new day. 🌌",
    "01": "1 AM — A quiet hour when most of the world sleeps. 💤",
    "06": "6 AM — Sunrise and a fresh start for many! 🌅",
    "12": "12 PM — Noon, when the sun is highest in the sky. ☀️",
    "15": "3 PM — Did you know? In ancient Rome, 3 PM was teatime! 🍵",
    "18": "6 PM — Time to wind down and relax. 🎧",
    "21": "9 PM — The moon is usually high by now. 🌕",
    "23": "Almost midnight — time flies! ⏳"
  };

  const hourKey = String(h).padStart(2, '0');
  const fact = timeFacts[hourKey] || `The time is now ${timeString}. Enjoy the moment!`;

  // Simulate API delay
  await new Promise((resolve) => setTimeout(resolve, 1000));

  factContainer.textContent = fact;
  geminiButton.disabled = false;
  geminiButton.textContent = 'Get Time Fact ✨';
}

  createLights(hourColumn);
  createLights(minuteColumn);
  createLights(secondColumn);
  updateClock();
  setInterval(updateClock, 1000);
  geminiButton.addEventListener('click', getTimeFact);
});
