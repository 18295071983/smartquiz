# Digital Nixie Tube Clock 🕰️

A hyper-realistic, web-based simulation of a vintage IN-12 Nixie Tube clock. This project uses pure HTML, CSS, and Vanilla JavaScript to recreate the warm glow, flickering neon gas, and "cathode poisoning" prevention mechanisms found in physical tube clocks.

| Digital Nixie Tube Clock |
|:---:|
| ![Digital Nixie Tube Clock](https://github.com/joeparadiso/nixie-tube-clock/blob/master/images/ProjectImage.png?raw=true) |
| *_Image of this Digital Nixie Tube clock project_* |

## 📖 Table of Contents
- [About the Project](#about-the-project)
- [What is a Nixie Tube?](#what-is-a-nixie-tube)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Installation & Usage](#installation--usage)
- [Project Structure](#project-structure)
- [Credits](#credits)

## 🧐 About the Project
This project brings the retro aesthetic of Cold War-era display technology to the modern web browser. Unlike standard digital clocks, this simulation focuses on **skeuomorphism**—replicating the physical imperfections of real hardware. It features glass tube reflections, random neon flickering, electric hum audio, and specific animations designed to mimic how actual Nixie tubes operate.

## 💡 What is a Nixie Tube?
A **Nixie tube** (short for *Numeric Indicator eXperimental No. 1*) is a cold-cathode display technology used extensively from the 1950s to the 1970s.

| Nixie Tube Clock |
|:---:|
| ![Real Nixie Tube Example](https://github.com/joeparadiso/nixie-tube-clock/blob/master/images/nixieImgExample.png?raw=true) |
| *_An example of a real Nixie Tube clock_* |

Unlike modern LEDs or LCDs, a Nixie tube contains a wire-mesh anode and multiple cathodes shaped like numerals (0-9) stacked inside a glass bulb filled with neon gas. When electricity is applied to a specific cathode, it glows with a characteristic orange-red "plasma" light.

## ✨ Features

### 🎨 Visual & Audio Realism
* **Neon Glow & Flicker:** Uses complex CSS `box-shadow` and `text-shadow` layers combined with randomized CSS animations to simulate the unstable nature of ionized neon gas.
* **Glass Tube Aesthetics:** CSS pseudo-elements (`::before`, `::after`) create static glare and reflections, simulating the curved glass surface of the tubes.
* **Soundscape:** Includes a background "electric hum" (`buzz.mp3`) to mimic the sound of high-voltage transformers used in vintage electronics.
* **Retro Background:** Styled with a hex-grid pattern and dark gradients to resemble a lab instrument or industrial equipment.

### ⚙️ Mechanics Simulation
* **Cathode Poisoning Prevention:** In real Nixie tubes, if a digit isn't used for a long time, it can become coated in sputtered metal ("poisoned"). This project simulates the maintenance cycle used to fix this: **Every 10 minutes**, the clock enters a rapid "slot machine" cycling mode, flashing through all digits 0-9 to keep them "active".
* **Dynamic Digit Rendering:** The Javascript builds the digit stack dynamically, ensuring the transition between numbers feels mechanical.

## 🛠️ Tech Stack
* **HTML5:** Semantic structure for the clock housing and tubes.
* **CSS3:** Heavy usage of:
    * Flexbox for layout.
    * Keyframe animations (`flicker`, `flash`).
    * `backdrop-filter` for glass diffusion.
    * Radial/Linear gradients for lighting effects.
* **JavaScript (ES6):**
    * `Date` object for time tracking.
    * `setInterval` for the core clock tick (1000ms).
    * Logic to trigger the cycling animation algorithm.

## 🚀 Installation & Usage

1.  **Clone the Repository**
    ```bash
    git clone [https://github.com/your-username/nixie-tube-clock.git](https://github.com/your-username/nixie-tube-clock.git)
    ```
2.  **Add Assets**
    * Ensure you have a background image named `hexmap3.png` in the root folder.
    * Ensure you have an audio file named `buzz.mp3` in the root folder.
3.  **Run the Project**
    * Simply open `index.html` in any modern web browser.
    * *Note: Due to browser autoplay policies, you may need to interact with the page (click anywhere) for the audio to start.*

## 📂 Project Structure

```text
nixie-tube-clock/
├── index.html      # Main markup for the clock and tubes
├── styles.css      # All styling, glow effects, and animations
├── app.js          # Clock logic and digit cycling mechanism
├── hexmap3.png     # Background texture for the tubes (Required)
├── buzz.mp3        # Electric hum sound effect (Required)
└── README.md       # Project documentation
