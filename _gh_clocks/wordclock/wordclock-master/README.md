wordclock
=========

A customizable word clock with to-the-minute granularity. Created using HTML, CSS and JavaScript.
Inspired by [Biegert & Funk's QLOCKTWO](http://www.qlocktwo.com/info.php?lang=en). Powered by your choice of JavaScript selector library: [jQuery](http://jquery.com), [jqMobi](http://www.jqmobi.com), or [Zepto](http://zeptojs.com).

Usage
-----

Usage is simple: Open index.html in your browser. Or [check out the live demo](https://f1337.github.io/wordclock/).

Screenshots
-----------

[![Black](https://raw.github.com/f1337/wordclock/master/images/black.png)](https://f1337.github.com/wordclock/)
[![Brown](https://raw.github.com/f1337/wordclock/master/images/brown.png)](https://f1337.github.com/wordclock/)
[![Denim](https://raw.github.com/f1337/wordclock/master/images/denim.png)](https://f1337.github.com/wordclock/)
[![Olive](https://raw.github.com/f1337/wordclock/master/images/olive.png)](https://f1337.github.com/wordclock/)
[![Raspberry](https://raw.github.com/f1337/wordclock/master/images/raspberry.png)](https://f1337.github.com/wordclock/)
[![Red](https://raw.github.com/f1337/wordclock/master/images/red.png)](https://f1337.github.com/wordclock/)
[![Steel](https://raw.github.com/f1337/wordclock/master/images/steel.png)](https://f1337.github.com/wordclock/)


Customization
-------------

Customize the color scheme by editing the `<nav>` segment in index.html:

    <nav>
        <a href="#" style="background-color: #262626; color: #F2F2F2" data-off-color="#595959" title="Salt &amp; Pepper">Salt &amp; Pepper</a>
        <a href="#" style="background-color: #44301C; color: #9CCFDD" data-off-color="#5E5043" title="Earth &amp; Sky">Earth &amp; Sky</a>
        <a href="#" style="background-color: #DF1A21; color: #FBE1A6" data-off-color="#FB6B58" title="Cherry Cordial">Cherry Cordial</a>
        <a href="#" style="background-color: #063540; color: #E5D9CF" data-off-color="#176273" title="Blue Jeans">Blue Jeans</a>
        <a href="#" style="background-color: #730240; color: #F2BC79" data-off-color="#BF1B39" title="Raspberry Sorbet">Raspberry Sorbet</a>
        <a href="#" style="background-color: #3D4010; color: #CFD936" data-off-color="#575931" title="Olive Martini">Olive Martini</a>
        <a href="#" style="background-color: #6F6B79; color: #E1DEEB" data-off-color="#583736" title="Zoolander">Zoolander</a>
    </nav>

To add more color schemes, simply copy and paste one of the lines above, then customize to taste. The inline styles define the background and highlight colors for the link itself and the clock. The `data-off-color` property is used to define the unhighlighted letters. [Adobe Kuhler](https://kuler.adobe.com/) is an excellent tool for choosing color schemes.


Compatibility
-------------

wordclock *should* run in the latest CSS3/HTML5-friendly browsers, but has only been tested using Safari (6.0.2), Chrome (16.0.912.77, 23.0.1271.64; see note below), FireFox (9.0.1, 11.0, 17.0) and Opera (12.11) on on Mac OS X (10.8.2) and Mobile Safari on iOS 6.

The latest CSS3 declarations used for the swatches cause the swatches to be un-clickable in the latest versions of Chrome. This is a [known bug](http://code.google.com/p/chromium/issues/detail?id=92917), which the Chromium team has apparently chosen [not to fix](http://code.google.com/p/chromium/issues/detail?id=86730). We'll keep tinkering with the CSS, but have no intention of introducing markup hacks to make Chrome play nice.

TODO
----

* Hyperlink to a given style based on hash.
* Verify (and possibly fix) Internet Explorer support (IE9+).


License
-------

Copyright © 2012 Michael R. Fleet.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
