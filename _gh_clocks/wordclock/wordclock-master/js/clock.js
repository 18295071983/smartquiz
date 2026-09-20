function Klock (clockSelector, clickSelector)
{
    // find the clock on the page
    this.clock = $('body');

    this.activeNodes = [];
    this.backgroundColor = '#262626';
    this.intervalToken = null;
    this.offColor = '#595959';
    this.onColor = '#f2f2f2';
    this.textShadow = '';

    // if clickSelector defined, setup the click hijack
    if ( clickSelector )
    {
        this.clickJackAllTheThings(clickSelector);
    }

    // set the initial style
    this.updateStyle();

    // set the initial time
    this.updateTime();
}



/****************************************************************************
* Static Properties
****************************************************************************/
/*
Link-lists below define grid positions for the various words on the clock.
The lists themselves are layout-specific: IOW, if new layouts are added,
    new link lists will need to be created.
*/

Klock.hours = [
  // row, left, len
  [ 1, 1, 3 ], // one
  [ 2, 1, 3 ], // two
  [ 1, 4, 5 ], // three
  [ 2, 4, 4 ], // four
  [ 1, 9, 4 ], // five
  [ 3, 1, 3 ], // six
  [ 2, 8, 5 ], // seven
  [ 3, 4, 5 ], // eight
  [ 3, 9, 4 ], // nine
  [ 5, 1, 3 ], // ten
  [ 4, 1, 6 ], // eleven
  [ 4, 7, 6 ]  // twelve
];

Klock.minTens = [
    // row, left, len
    [  8, 6, 1 ], // o
    [ 12, 8, 5 ], // -teen
    [  6, 1, 6 ], // twenty
    [  7, 1, 6 ], // thirty
    [  7, 8, 5 ], // forty
    [  8, 1, 5 ] // fifty
];

Klock.minOnes = [
  // row, left, len
  [  8, 7, 6 ], // ’clock
  [  9, 1, 3 ], // one
  [ 10, 1, 3 ], // two
  [  9, 4, 5 ], // three
  [ 10, 4, 4 ], // four
  [  9, 9, 4 ], // five
  [ 11, 1, 3 ], // six
  [ 10, 8, 5 ], // seven
  [ 11, 4, 5 ], // eight
  [ 11, 9, 4 ], // nine


  [ 5, 10, 3 ], // ten
  [ 12, 1, 6 ], // eleven
  [  6, 7, 6 ],  // twelve

  [  7, 1, 5 ], // thirteen
  [ 10, 4, 4 ], // fourteen
  [  8, 1, 3 ], // fifteen
  [ 11, 1, 3 ], // sixteen
  [ 10, 8, 5 ], // seventeen
  [ 11, 4, 5 ], // eighteen
  [ 11, 9, 4 ] // nineteen
];



/****************************************************************************
* Instance Methods
****************************************************************************/



// hijack click events for the given selector
Klock.prototype.clickJackAllTheThings = function (selector)
{
    var self = this;
    // the following syntax works for jqMobi, jQuery, and Zepto.
    $(selector).on('click', function (e) { self.elementClicked(e); delete self; });
}



Klock.prototype.elementClicked = function (e)
{
    var link = $(e.currentTarget);

    // get the new background color from the clicked element's style
    var newBackgroundColor = link.css('background-color');
    if ( newBackgroundColor ) this.backgroundColor = newBackgroundColor;

    // get the new "off" color from the clicked element's data-off-color property
    var newOffColor = link.data('off-color');
    if ( newOffColor ) this.offColor = newOffColor;

    // get the new "on" color from the clicked element's style
    var newOnColor = link.css('color');
    if ( newOnColor ) this.onColor = newOnColor;

    // update the style settings
    this.updateStyle();

    // apply the new style to the highlighted nodes
    for (var n = 0; n < this.activeNodes.length; n++)
    {
        this.activeNodes[n].css('color', this.onColor);
    }
};



Klock.prototype.highlightElementsAtIndices = function (indices)
{
    var hourRow = indices[0];
    var row = $('#WordClock *:nth-child(' + hourRow + ')');
    var hourLeft = indices[1];
    var hourRight = hourLeft + indices[2];
    for (var i = hourLeft; i < hourRight; i++)
    {
        var child = row.children("*:nth-child(" + i + ")");
        this.activeNodes.push(child);
        child.css('color', this.onColor);
        // double text-shadow, using off & on colors:
        child.css('text-shadow', this.textShadow);
        // child.addClass("on");
    }
};



Klock.prototype.updateStyle = function ()
{
    // double text-shadow, using off & on colors:
    this.textShadow = '0 0 0.8rem ' + this.offColor + ', 0 0 0.8rem ' + this.onColor;
    
    this.clock.css({
       'background-color': this.backgroundColor,
       'color': this.offColor
    });
};



Klock.prototype.updateTime = function ()
{
    // console.profile();

    //establish what the time is
    var currentTime = new Date();
    var hour = currentTime.getHours() - 1;
    if (hour == -1) { hour = 11; }
    var minute = currentTime.getMinutes();
    var ampm = "am";
    // hour > 11 => after noon
    if (hour > 11)
    {
        ampm = "pm";
        hour = hour - 12;
    }
    // 11 => noon => 12pm
    if (hour == 11)
    {
        ampm = "pm";
    }

    // un-highlight prior active nodes
    while (this.activeNodes.length)
    {
        var node = this.activeNodes.shift();
        node.css('color', '');
        node.css('text-shadow', '');
        // node.removeClass("on");
    }

    // highlight the hour
    this.highlightElementsAtIndices(Klock.hours[hour]);

    // highlight the minute's ones
    var minTen = Math.floor(minute / 10);
    var minOne = (minute % 10);

    // handle tens and teens in a special manner
    // 10, 11, and 12 => treated as extra "ones", with no "tens"
    // 13+ => treated as extra "ones", includes the "ten"
    if ( minTen == 1 )
    {
        minTen = (minOne < 3 ? 0 : 1);
        minOne += 10;
    }

    // highlight the minute's "ones"
    // if an even multiple of 10 minutes, skip the "ones"
    if ( ! (minTen && minOne == 0) )
    {
        this.highlightElementsAtIndices(Klock.minOnes[minOne]);
    }

    // highlight the minute's "tens"
    // handle 10, 11, 12 in a special manner (see above)
    if ( minOne < 10 || minOne > 12 )
    {
        this.highlightElementsAtIndices(Klock.minTens[minTen]);
    }

    var self = this;
    // this method has been run once before
    // assume we're at the "top of the minute" now
    // clear the one-time interval and setup the per-minute interval
    if ( this.intervalToken == -1 )
    {
        // set the interval to run every minute
        this.intervalToken = setInterval(function () { self.updateTime(); delete self; }, 60000);
        // console.log('set interval with token: ' + this.intervalToken);
        
    }
    // run this function again at the top of the minute
    // but only do this ONCE!
    else if ( this.intervalToken == null )
    {
        var second = currentTime.getSeconds();
        var nextInterval = Math.max((60 - second), 1);
        setTimeout(function () { self.updateTime(); delete self; }, (nextInterval * 1000));
        this.intervalToken = -1;
        // console.log('set timeout with token: ' + this.intervalToken);
    }

    // console.profileEnd();
};


var myWordClock = null;

$(function ()
{
    myWordClock = new Klock('#WordClock');
    myWordClock.clickJackAllTheThings('nav > a');
});
