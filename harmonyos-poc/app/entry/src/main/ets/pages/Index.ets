import * as nativeProbe from 'libzjuprobe_bridge.so';

interface ProbeResult {
  loaded: boolean;
  version: string;
  addResult: number;
  error: string;
}

@Entry
@Component
struct Index {
  @State resultText: string = '尚未运行';

  private runProbe(): void {
    try {
      const result: ProbeResult = nativeProbe.probe();
      this.resultText = [
        'loaded=' + result.loaded,
        'version=' + result.version,
        'addResult=' + result.addResult,
        'error=' + result.error
      ].join('\n');
    } catch (error) {
      this.resultText = 'NAPI 调用失败：' + JSON.stringify(error);
    }
  }

  build() {
    Column({ space: 16 }) {
      Text('ZJU Connect')
        .fontSize(28)
        .fontWeight(FontWeight.Bold)
      Text('HarmonyOS native PoC · Go runtime + Node-API')
        .fontSize(14)
        .fontColor(Color.Gray)
      Button('Run Go + NAPI probe')
        .onClick(() => this.runProbe())
      Text(this.resultText)
        .fontSize(14)
        .fontFamily('monospace')
        .width('100%')
    }
    .width('100%')
    .height('100%')
    .padding(24)
    .justifyContent(FlexAlign.Center)
  }
}
