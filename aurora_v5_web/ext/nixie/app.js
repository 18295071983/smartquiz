function updateClock() {
  const now = new Date();

  let hours = now.getHours() % 12 || 12;
  let minutes = now.getMinutes();
  let seconds = now.getSeconds();

  const timeParts = [
    Math.floor(hours / 10),
    hours % 10,
    Math.floor(minutes / 10),
    minutes % 10,
    Math.floor(seconds / 10),
    seconds % 10,
  ];

  const digitContainers = [
    "hours-tens",
    "hours-ones",
    "minutes-tens",
    "minutes-ones",
    "seconds-tens",
    "seconds-ones",
  ];

  const cycleDigits = (callback) => {
    let cycleCount = 0; // Keeps track of how many cycles have occurred
    const interval = setInterval(() => {
      // Loop through each digit container (e.g., hours-tens, hours-ones, etc.)
      digitContainers.forEach((id) => {
        const container = document.getElementById(id);
        container.innerHTML = ""; // Clear old digits before adding new ones

        // Create and append each cycling digit (0–9)
        for (let i = 0; i < 10; i++) {
          const digitElement = document.createElement("div");
          digitElement.classList.add("digit"); // Applies the digit styling
          digitElement.textContent = (i + cycleCount) % 10; // Properly cycles through all digits (0–9)

          // Illuminate the current digit being displayed
          if (i === cycleCount % 10) {
            digitElement.classList.add("illuminated"); // Apply glow effect only to the active digit
          }

          container.appendChild(digitElement); // Add the digit to the container
        }
      });

      cycleCount++; // Increment the cycle counter after each iteration

      // Stop cycling after completing one full pass (0–9) and show the correct time
      if (cycleCount > 10) {
        clearInterval(interval); // Stops the cycling
        callback(); // Calls the main function to display the correct time
      }
    }, 100); // The digits will update every X milliseconds
  };

  digitContainers.forEach((id, index) => {
    const container = document.getElementById(id);
    const previousValue = container.getAttribute("data-last-value");

    container.innerHTML = ""; // Clear old digits

    for (let i = 0; i < 10; i++) {
      const digitElement = document.createElement("div");
      digitElement.classList.add("digit");
      digitElement.textContent = i;

      if (i === timeParts[index]) {
        digitElement.classList.add("illuminated");

        // If the number changed, add a flash effect
        if (previousValue !== String(i)) {
          digitElement.classList.add("flash");
        }

        // Apply subtle random flickering effect
        const randomFlickerTime = (Math.random() * 2 + 1.5).toFixed(2) + "s";
        digitElement.style.animationDuration = randomFlickerTime;
      }

      container.appendChild(digitElement);
    }

    container.setAttribute("data-last-value", timeParts[index]);
  });

  // Trigger digit cycling every ten minutes
  if (now.getMinutes() % 10 === 0 && seconds === 0) {
    cycleDigits(updateClock);
  }
}

// Update clock every second
setInterval(updateClock, 1000);
updateClock();
