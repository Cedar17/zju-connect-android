import { vpnExtension, VpnExtensionAbility } from '@kit.NetworkKit';
import { Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import * as nativeProbe from 'libzjuprobe_bridge.so';

const DOMAIN: number = 0x2026;
const TAG: string = 'ZjuVpnPoC';

/**
 * P1 only: prove that the system grants a TUN fd and that our Go runtime
 * can access that fd in the VPN extension process.
 *
 * 198.18.254.0/24 is an isolated benchmarking range. We intentionally do not
 * configure a default route, DNS override, or any Zhejiang University routes.
 * This experiment cannot provide a working VPN yet.
 */
export default class ZjuVpnAbility extends VpnExtensionAbility {
  private vpnConnection: vpnExtension.VpnConnection | undefined;

  onCreate(_want: Want): void {
    hilog.info(DOMAIN, TAG, 'VPN extension created');
    this.startProbe();
  }

  private async startProbe(): Promise<void> {
    try {
      const config: vpnExtension.VpnConfig = {
        addresses: [
          { address: { address: '198.18.0.2', family: 1 }, prefixLength: 32 }
        ],
        routes: [
          {
            interface: 'vpn-tun',
            destination: {
              address: { address: '198.18.254.0', family: 1 },
              prefixLength: 24
            },
            gateway: { address: '0.0.0.0', family: 1 },
            hasGateway: false,
            isDefaultRoute: false
          }
        ],
        mtu: 1400,
        isIPv4Accepted: true,
        isIPv6Accepted: false,
        isBlocking: false
      };

      this.vpnConnection = vpnExtension.createVpnConnection(this.context);
      const tunFd: number = await this.vpnConnection.create(config);
      hilog.info(DOMAIN, TAG, 'VPN create returned fd=%{public}d', tunFd);

      // This enters the Go runtime from the extension process. It duplicates
      // and immediately closes the duplicate, never consuming any packets.
      const goCanAccessFd: boolean = nativeProbe.inspectFd(tunFd);
      if (!goCanAccessFd) {
        throw new Error('Go runtime could not duplicate the TUN fd');
      }
      hilog.info(DOMAIN, TAG, 'P1_OK: system TUN fd accessible from Go');
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'P1_FAILED: %{public}s', String(error));
      if (this.vpnConnection) {
        const connection = this.vpnConnection;
        this.vpnConnection = undefined;
        try {
          await connection.destroy();
        } catch (_) {
          hilog.warn(DOMAIN, TAG, 'VPN cleanup after failure was not successful');
        }
      }
    }
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, 'VPN extension destroyed');
    const connection = this.vpnConnection;
    this.vpnConnection = undefined;
    if (connection) {
      connection.destroy().catch((error: Error) => {
        hilog.warn(DOMAIN, TAG, 'VPN destroy failed: %{public}s', String(error));
      });
    }
  }
}
