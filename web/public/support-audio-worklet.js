// Microphone capture for the remote-support voice call. Runs in the audio thread: takes the context's sample
// rate (44.1 / 48 kHz…), brings it down to 16 kHz mono and posts 320-sample Int16 frames (20 ms) to the page.
// A plain file under /public because the console's CSP only runs scripts from its own origin.
/* global AudioWorkletProcessor, registerProcessor, sampleRate */
const TARGET_RATE = 16000;
const FRAME = 320;

class DcSupportCapture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.acc = 0;
    this.count = 0;
    this.phase = 0;
    this.last = 0;
    this.frame = new Int16Array(FRAME);
    this.filled = 0;
  }

  push(value) {
    const v = value > 1 ? 1 : value < -1 ? -1 : value;
    this.frame[this.filled++] = Math.round(v * 32767);
    if (this.filled === FRAME) {
      const out = this.frame;
      this.port.postMessage(out.buffer, [out.buffer]);
      this.frame = new Int16Array(FRAME);
      this.filled = 0;
    }
  }

  process(inputs) {
    const input = inputs[0];
    if (!input || input.length === 0 || !input[0]) return true;
    const channels = input.length;
    const length = input[0].length;
    for (let i = 0; i < length; i++) {
      let s = 0;
      for (let c = 0; c < channels; c++) s += input[c][i];
      s /= channels;
      // Average the input samples that fall into one output sample (a box low-pass), at the exact rate ratio.
      this.acc += s;
      this.count++;
      this.phase += TARGET_RATE;
      while (this.phase >= sampleRate) {
        this.phase -= sampleRate;
        if (this.count > 0) {
          this.last = this.acc / this.count;
          this.acc = 0;
          this.count = 0;
        }
        this.push(this.last);
      }
    }
    return true;
  }
}

registerProcessor('dc-support-capture', DcSupportCapture);
