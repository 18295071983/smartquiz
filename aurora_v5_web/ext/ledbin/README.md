[![Auto Assign](https://github.com/DON-KD2QQV/binary-clock/actions/workflows/auto-assign.yml/badge.svg?branch=main)](https://github.com/DON-KD2QQV/binary-clock/actions/workflows/auto-assign.yml)
[![Proof HTML](https://github.com/DON-KD2QQV/binary-clock/actions/workflows/proof-html.yml/badge.svg)](https://github.com/DON-KD2QQV/binary-clock/actions/workflows/proof-html.yml)
[![pages-build-deployment](https://github.com/DON-KD2QQV/binary-clock/actions/workflows/pages/pages-build-deployment/badge.svg?branch=main)](https://github.com/DON-KD2QQV/binary-clock/actions/workflows/pages/pages-build-deployment)

🕒 Binary Clock with Tailwind CSS + Time Facts

A visual binary LED-style clock with a real-time digital display, animated UI using Tailwind CSS, and a fun "Time Fact" button — all built with pure HTML, CSS, and JavaScript.

🌟 Features

LED-style binary display for hours, minutes, and seconds

Responsive design with Tailwind CSS

Animated lights with real-time updates

Time fact button that gives fun trivia based on the current hour

No backend or API key required — works fully on GitHub Pages

📁 Project Structure

/
├── index.html          # Main HTML page (was binaryclock.html)
├── script.js           # Binary clock logic and time fact generator
├── dist/output.css     # Tailwind-generated CSS
├── src/styles.css      # Tailwind source
├── tailwind.config.js
├── postcss.config.js
├── package.json
└── README.md

🚀 Live Demo

🔗 View Live on GitHub Pages
   don-kd2qqv.github.io/binary-clock/

📦 Getting Started (Dev Mode)

Optional: only if you're using Tailwind locally

npm install
npx tailwindcss -i ./src/styles.css -o ./dist/output.css --watch

Then open index.html in your browser.

👤 Author and Contributor

Don Bryant — github.com/don-kd2qqv

📄 License

MIT — free to use, modify, and share.
