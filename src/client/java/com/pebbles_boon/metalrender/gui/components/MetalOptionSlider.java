package com.pebbles_boon.metalrender.gui.components;
import java.util.function.Consumer;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
public class MetalOptionSlider extends SliderWidget {
  private final float minValue;
  private final float maxValue;
  private final float step;
  private final Consumer<Float> onChange;
  public MetalOptionSlider(int x, int y, int width, int height, Text text,
      float min, float max, float step, float currentValue,
      Consumer<Float> onChange) {
    super(x, y, width, height, text, normalize(currentValue, min, max));
    this.minValue = min;
    this.maxValue = max;
    this.step = step;
    this.onChange = onChange;
    updateMessage();
  }
  private static double normalize(float value, float min, float max) {
    if (max <= min)
      return 0;
    return (value - min) / (max - min);
  }
  public float getRealValue() {
    float raw = minValue + (float) this.value * (maxValue - minValue);
    if (step > 0) {
      raw = Math.round(raw / step) * step;
    }
    return Math.max(minValue, Math.min(maxValue, raw));
  }
  @Override
  protected void updateMessage() {
    float v = getRealValue();
    if (step >= 1.0f) {
      setMessage(Text.literal(String.valueOf((int) v)));
    } else {
      setMessage(Text.literal(String.format("%.2f", v)));
    }
  }
  @Override
  protected void applyValue() {
    if (onChange != null) {
      onChange.accept(getRealValue());
    }
  }
}
