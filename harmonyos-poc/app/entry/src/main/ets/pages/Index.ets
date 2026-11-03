import { Want } from '@kit.AbilityKit';
import { vpnExtension } from '@kit.NetworkKit';
import * as nativeProbe from 'libzjuprobe_bridge.so';

interface ProbeResult {
  loaded: boolean;
  version: string;
  addResult: number;
  error: string;
}

const BUNDLE_NAME: string = 'io.github.cedar17.zjuconnect.harmony';
const VPN_ABILITY_NAME: string = 'ZjuVpnAbility';

function vpnWant(): Want {
  return {
    deviceId: '',
    bundleName: BUNDLE_NAME,
    abilityName: VPN_ABILITY_NAME
  };
}

@Entry
@Component
struct Index {
  @State resultText: string = '尚未运行';
  @State vpnStatus: string = '未请求';

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
      this.resultText = 'N-API 调用失败：' + String(error);
    }
  }

  private async startVpnProbe(): Promise<void> {
    this.vpnStatus = '正在请求系统 VPN 授权…';
    try {
      await vpnExtension.startVpnExtensionAbility(vpnWant());
      // Starting the extension is not evidence that TUN creation succeeded.
      this.vpnStatus = '扩展启动请求已完成；请检查 hilog 中的 P1_OK / P1_FAILED';
    } catch (error) {
      this.vpnStatus = '扩展启动失败：' + String(error);
    }
  }

  private async stopVpnProbe(): Promise<void> {
    this.vpnStatus = '正在停止 VPN 扩展…';
    try {
      await vpnExtension.stopVpnExtensionAbility(vpnWant());
      this.vpnStatus = '系统已收到停止请求';
    } catch (error) {
      this.vpnStatus = '停止请求失败：' + String(error);
    }
  }

  build() {
    Column({ space: 16 }) {
      Text('ZJU Connect')
        .fontSize(28)
        .fontWeight(FontWeight.Bold)
      Text('HarmonyOS P0/P1 · Go runtime + VPN Extension')
        .fontSize(14)
        .fontColor(Color.Gray)
      Button('P0 · Run Go probe')
        .onClick(() => this.runProbe())
      Text(this.resultText)
        .fontSize(14)
        .width('100%')
      Button('P1 · Start isolated VPN probe')
        .onClick(() => { this.startVpnProbe(); })
      Button('Stop VPN probe')
        .onClick(() => { this.stopVpnProbe(); })
      Text(this.vpnStatus)
        .fontSize(14)
        .width('100%')
      Text('P1 仅测试系统虚拟网卡和 Go 文件描述符，不接管默认流量，不提供真实校网访问。')
        .fontSize(12)
        .fontColor(Color.Gray)
        .width('100%')
    }
    .width('100%')
    .height('100%')
    .padding(24)
    .justifyContent(FlexAlign.Center)
  }
}
