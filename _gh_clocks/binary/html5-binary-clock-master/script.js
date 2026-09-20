/** 
  BULBS ARRANGEMENT:

   .  h18  .  m18  .  s18
   .  h14 m04 m14 s04 s14
  h02 h12 m02 m12 s02 s12
  h01 h11 m01 m11 s01 s11
 */
const h0 = { h01: elem('h0-1'), h02: elem('h0-2') }
const h1 = { h11: elem('h1-1'), h12: elem('h1-2'), h14: elem('h1-4'), h18: elem('h1-8') }
const m0 = { m01: elem('m0-1'), m02: elem('m0-2'), m04: elem('m0-4') }
const m1 = { m11: elem('m1-1'), m12: elem('m1-2'), m14: elem('m1-4'), m18: elem('m1-8') }
const s0 = { s01: elem('s0-1'), s02: elem('s0-2'), s04: elem('s0-4') }
const s1 = { s11: elem('s1-1'), s12: elem('s1-2'), s14: elem('s1-4'), s18: elem('s1-8') }

/**
  Tells which bulb/s to be turned on. Example:
    input  = 7
    output = [4, 2, 1]
 */
const bin = memoize((num) => {
  let bin = []
  let i   = 0
  num     = parseInt(num)

  while (not0(num)) {
    if (divBy2(num)) { push(bin, power(2, i)) }
    num = div2(num)
    i += 1
  }

  return bin
})

loadHandler = () => {
  const off   = addClass('off')
  const on    = remClass('off')
  const pad   = n => n < 10 ? `0${n}` : n
  // Convert hours, minutes and seconds to string
  const hrs   = compose(pad, toString, hours)
  const min   = compose(pad, toString, minutes)
  const sec   = compose(pad, toString, seconds)
  // If True turn bulb on, otherwise turn it off
  const light = (b, e) => { if (b) on(e); else off(e) } 
  // 1st Digit
  const _1st  = compose(contains, bin, head)
  // 2nd Digit
  const _2nd  = compose(contains, bin, tail)

  // Current Second(1st digit), Hour, Minute
  let cs0, ch, cm

  function update() {
    const d = new Date()
    const h = hrs(d)
    const m = min(d)
    const s = sec(d)

    const s1Bulb = _2nd(s)

    forEach(i => light(s1Bulb(i), s1[`s1${i}`]), [1, 2, 4, 8])

    // If seconds(1st digit) was not changed, don't do anything
    if (cs0 !== head(s)) {
      const s0Bulb = _1st(s)

      forEach(i => light(s0Bulb(i), s0[`s0${i}`]), [1, 2, 4])

      cs0 = head(s)
    }

    // If minutes was not changed, don't do anything
    if (cm !== m) {
      const m0Bulb = _1st(m)
      const m1Bulb = _2nd(m)

      forEach(i => light(m0Bulb(i), m0[`m0${i}`]), [1, 2, 4])
      forEach(i => light(m1Bulb(i), m1[`m1${i}`]), [1, 2, 4, 8])

      cm = m
    }

    // If hours was not changed, don't do anything
    if (ch !== h) {
      const h0Bulb = _1st(h)
      const h1Bulb = _2nd(h)

      forEach(i => light(h0Bulb(i), h0[`h0${i}`]), [1, 2])
      forEach(i => light(h1Bulb(i), h1[`h1${i}`]), [1, 2, 4, 8])

      ch = h
    }
  }

  setInterval(update, 1000)
}

listener('load', window, loadHandler)
