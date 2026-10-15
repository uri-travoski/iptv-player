# Models

`nsfw-mobilenet-v2-int8.tflite`: the image check of the TV home screen slider (Spotlight). Every
backdrop is scored by it on the box before it can be shown; anything scored as sexy, nude or
explicit, even slightly, is left out. The app downloads this file once and checks its SHA-256
(`Spotlight.MODEL_SHA256`).

It is [GantMan/nsfw_model](https://github.com/GantMan/nsfw_model) release 1.2.0
(`mobilenet_v2_140_224`, classes: drawings, hentai, neutral, porn, sexy), converted from its
SavedModel to TensorFlow Lite with dynamic-range (int8 weight) quantization:

```python
conv = tf.lite.TFLiteConverter.from_saved_model("mobilenet_v2_140_224")
conv.optimizations = [tf.lite.Optimize.DEFAULT]
```

Input: 224×224 RGB, values 0–1. Output: 5 probabilities.

`nsfw-mobilenet-v2.onnx`: the same model for the Linux app (ONNX Runtime; TensorFlow Lite has no
desktop Java build), converted with `python -m tf2onnx.convert --saved-model mobilenet_v2_140_224
--opset 13`. Kept in float: ONNX Runtime's dynamic quantization changed its answers. Checked
against the TFLite file on the same images (`DesktopSpotlight.MODEL_SHA256`).

## License

MIT License, Copyright (c) 2020 The nsfw_model Developers

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
