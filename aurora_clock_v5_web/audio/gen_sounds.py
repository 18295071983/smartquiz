# -*- coding: utf-8 -*-
"""极光时钟 内置音效文件生成器（离线合成，CC0）
生成 16bit/22050Hz/mono WAV：报时/按键/提示/滴答
运行: python gen_sounds.py
"""
import math, wave, struct, os

RATE = 22050
OUT = os.path.dirname(os.path.abspath(__file__))

def _env(decay):
    return math.exp(-decay * t) if decay else 1.0

def render(dur, fn, decay=0):
    """fn(t) -> [-1,1]"""
    n = int(RATE * dur)
    buf = bytearray()
    for i in range(n):
        tt = i / RATE
        v = fn(tt)
        v *= math.exp(-decay * tt) if decay else 1.0
        v = max(-1.0, min(1.0, v))
        buf += struct.pack('<h', int(v * 32767))
    return bytes(buf)

def tone_sine(f, dur, vol=0.5, decay=6, harmonics=None, vib_rate=0, vib_depth=0):
    def fn(t):
        ph = 2 * math.pi * f * t
        if vib_rate:
            ph += vib_depth * math.sin(2 * math.pi * vib_rate * t)
        v = 0.0
        if harmonics:
            for hf, hv in harmonics:
                v += hv * math.sin(ph * hf)
        else:
            v = math.sin(ph)
        return v * vol
    return render(dur, fn, decay)

def tone_wave(f, dur, vol=0.5, wave='sine', decay=8, glide_to=None):
    def fn(t):
        fr = f
        if glide_to:
            fr = f + (glide_to - f) * (t / dur)
        w = 2 * math.pi * fr * t
        if wave == 'square':
            v = 1.0 if math.sin(w) > 0 else -1.0
        elif wave == 'triangle':
            v = 2 / math.pi * math.asin(math.sin(w))
        else:
            v = math.sin(w)
        return v * vol
    return render(dur, fn, decay)

def mix(*parts):
    """parts: [(bytes, delay_s)] -> 拼接混合（PCM 相加）"""
    total = max(len(p) + int(d * RATE) for p, d in parts)
    buf = bytearray(total)
    for p, d in parts:
        off = int(d * RATE)
        for i in range(0, len(p) - 1, 2):
            idx = off + i
            if idx + 1 >= total: break
            v = struct.unpack_from('<h', buf, idx)[0]
            w = struct.unpack_from('<h', p, i)[0]
            struct.pack_into('<h', buf, idx, max(-32767, min(32767, v + w)))
    return bytes(buf)

def concat(*parts):
    return b''.join(parts)

def save(name, data):
    path = os.path.join(OUT, name)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(data)
    print('%-22s %6.1f KB' % (name, os.path.getsize(path) / 1024))

# ---------- 报时 ----------
# 西敏寺钟（4 音符下行，钟声泛音）
west = b''
for f in (659.25, 523.25, 587.33, 392.00):
    west += tone_sine(f, 0.62, 0.5, decay=3.2, harmonics=[(1, 1), (2.0, .32), (2.4, .4), (3.6, .26), (4.8, .14)])
    west += b'\x00\x00' * int(RATE * 0.16)
save('chime_west.wav', west)

# 铜钟
save('chime_bell.wav', tone_sine(196.0, 2.4, 0.55, decay=1.9, harmonics=[(1, 1), (2.0, .5), (2.7, .42), (3.9, .3), (5.2, .16)]) +
     tone_sine(294.0, 2.0, 0.35, decay=2.2, harmonics=[(1, 1), (2.0, .4), (3.5, .25)]))

# 钢琴琶音 C5 E5 G5 C6
piano = b''
for f in (523.25, 659.25, 783.99, 1046.5):
    piano += tone_sine(f, 0.55, 0.5, decay=4.5, harmonics=[(1, 1), (2, .42), (3, .2), (4, .1)])
    piano += b'\x00\x00' * int(RATE * 0.09)
save('chime_piano.wav', piano)

# 玻璃杯（高音 + 颤音）
save('chime_glass.wav', tone_sine(1318.5, 1.1, 0.4, decay=6.5, harmonics=[(1, 1), (2.7, .3)], vib_rate=6, vib_depth=.012) +
     tone_sine(1760.0, 0.8, 0.28, decay=7.5, harmonics=[(1, 1), (3, .2)], vib_rate=5, vib_depth=.01))

# 电子哔（3 短音）
beep = b''
for i in range(3):
    beep += tone_wave(880, 0.13, 0.3, 'square', decay=0)
    beep += b'\x00\x00' * int(RATE * 0.07)
save('chime_beep.wav', beep)

# ---------- 按键音 ----------
save('click_soft.wav', mix((tone_wave(1180, .07, .22, 'triangle', 0), 0),
                            (tone_sine(1760, .05, .1, decay=0), .028)))
save('click_pop.wav', mix((tone_sine(660, .055, .22, decay=0), 0),
                           (tone_sine(990, .045, .13, decay=0), .04)))
save('click_wood.wav', mix((tone_wave(430, .05, .16, 'square', 0), 0),
                            (tone_sine(210, .08, .2, decay=0), .022)))
save('click_digital.wav', mix((tone_wave(1420, .032, .14, 'square', 0), 0),
                               (tone_wave(900, .042, .11, 'square', 0), .026)))
save('click_drop.wav', mix((tone_wave(1900, .06, .18, 'sine', 0, glide_to=1500), 0),
                            (tone_wave(720, .1, .12, 'sine', 0, glide_to=560), .05)))

# ---------- 提示 ----------
save('swipe.wav', mix((tone_wave(340, .11, .22, 'sine', 0, glide_to=760), 0),
                       (tone_sine(920, .1, .12, decay=0), .05)))
save('ok.wav', mix((tone_sine(784, .07, .24, decay=0), 0),
                    (tone_sine(1175, .11, .18, decay=0), .07)))
save('err.wav', mix((tone_wave(240, .13, .18, 'sawtooth' if False else 'square', 0), 0),
                     (tone_wave(185, .18, .13, 'square', 0), .11)))
save('tick.wav', tone_wave(1800, .045, .16, 'square', 0, glide_to=700))

print('done ->', OUT)
