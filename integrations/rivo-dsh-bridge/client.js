/* DSH loads browser plugins through its client module factory. */
window.__ModuleLoader__.load({
  id: 'rivo-dsh-bridge',
  factory(require) {
    const { createElement: h, useEffect, useRef, useState } = require('react');
    const route = '/api/rivo-relay/config';
    const namespace = 'rivoRelay';
    const locales = {
      en: {
        settings: 'Advanced settings', status: 'Connection status', pairing: 'Pair a device', loading: 'Checking connection…',
        pairingUnavailable: 'Pairing is available when Relay is connected.',
        bridgeSocket: 'DSH bridge socket', adminSocket: 'Relay admin socket',
        bridgeHint: 'Leave blank to use the DSH default.',
        adminHint: 'Leave blank to find Relay in the default location.',
        save: 'Save', refresh: 'Refresh', generate: 'Generate QR code', regenerate: 'Generate a new code',
        bridgeReady: 'DSH bridge listening', bridgeOffline: 'DSH bridge socket unavailable',
        unconfigured: 'Configure the Relay admin socket to connect.',
        relay_unavailable: 'Local Relay is unavailable. Check whether the service is running.',
        bridge_unavailable: 'Check the DSH bridge socket path and its directory permissions.',
        bridge_disconnected: 'Relay is waiting for the DSH bridge.',
        wrong_bridge: 'Relay is connected to a different DSH Host.',
        unsupported_route: 'This Relay needs a direct connection URL to pair here.',
        ready: 'Relay connected', updating: 'Relay is updating. Try again when it is available.',
        invalid_settings: 'Use separate absolute socket paths of up to 100 bytes and private directories.',
        save_failed: 'The profile could not save these settings. Refresh and try again.',
        request_failed: 'The plugin did not respond. Refresh and try again.',
        directUrl: 'Direct URL', scan: 'In the Rivo Relay app, open Connections and scan this code.',
        qrAlt: 'Rivo Relay device pairing QR code', expiry: 'Expires at',
        consumed: 'The device completed pairing.', expired: 'This pairing code expired.',
      },
      zh: {
        settings: '高级配置', status: '连接状态', pairing: '扫码配对', loading: '正在检查连接…',
        pairingUnavailable: 'Relay 连接成功后即可配对。',
        bridgeSocket: 'DSH 桥接 socket', adminSocket: 'Relay 管理 socket',
        bridgeHint: '留空时使用 DSH 默认路径。',
        adminHint: '留空时使用默认路径。',
        save: '保存', refresh: '刷新', generate: '生成配对二维码', regenerate: '重新生成二维码',
        bridgeReady: 'DSH 桥接已监听', bridgeOffline: 'DSH 桥接 socket 不可用',
        unconfigured: '请配置 Relay 管理 socket。',
        relay_unavailable: '本机 Relay 未连接。请确认 Relay 服务正在运行。',
        bridge_unavailable: '请检查 DSH 桥接路径及目录权限。',
        bridge_disconnected: 'Relay 正在等待 DSH 桥接。',
        wrong_bridge: 'Relay 当前连接的是另一个 DSH Host。',
        unsupported_route: '请为此 Relay 配置直连地址后再配对。',
        ready: 'Relay 已连接', updating: 'Relay 正在更新，请稍后重试。',
        invalid_settings: '请使用不同的绝对 socket 路径，每条最多 100 字节，且放在私有目录中。',
        save_failed: '无法保存到当前 DSH 配置。请刷新后重试。',
        request_failed: '插件未响应。请刷新后重试。',
        directUrl: '直连地址', scan: '在 Rivo Relay APP 的连接管理中扫描此二维码。',
        qrAlt: 'Rivo Relay 设备配对二维码', expiry: '有效至',
        consumed: '设备已完成配对。', expired: '此配对码已过期。',
      },
    };
    const inputStyle = { display: 'block', width: '100%', boxSizing: 'border-box', marginTop: 5, padding: '7px 9px',
      border: '1px solid var(--border, #8886)', borderRadius: 7, background: 'transparent', color: 'inherit',
      fontFamily: 'monospace', fontSize: '0.875em' };
    const headingStyle = { margin: '0 0 9px', fontSize: '1em', fontWeight: 600 };
    const buttonStyle = { padding: '5px 12px', borderRadius: 7, border: '1px solid var(--border, #8886)',
      background: 'transparent', color: 'inherit', font: 'inherit' };
    const buttonsStyle = { display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 10 };
    const panelStyle = { padding: '16px 20px', borderRadius: 12, border: '1px solid var(--border, #8885)' };
    const primaryStyle = { ...buttonStyle, background: 'var(--dsw-alias-action-primary, #2563eb)',
      color: '#fff', fontWeight: 600, borderColor: 'transparent' };

    async function api(signal, body, pairingId) {
      const path = pairingId ? `${route}?pairingId=${pairingId}` : route;
      const response = await fetch(path, body ? { method: 'POST', signal,
        headers: { 'content-type': 'application/json', 'x-rivo-relay': '1' }, body: JSON.stringify(body) } : { signal });
      const data = await response.json();
      if (!response.ok) throw new Error(data.state);
      return data;
    }

    function SettingsPage({ t }) {
      const [status, setStatus] = useState(null);
      const [draft, setDraft] = useState(null);
      const [pairing, setPairing] = useState(null);
      const [error, setError] = useState('');
      const [busy, setBusy] = useState(true);
      const lifetime = useRef(null);

      async function run(action, controller = lifetime.current) {
        setBusy(true);
        setError('');
        try {
          await action(controller.signal);
        } catch (failure) {
          if (!controller.signal.aborted) {
            setError(['invalid_settings', 'save_failed', 'updating', 'relay_unavailable', 'bridge_unavailable',
              'unconfigured', 'bridge_disconnected', 'wrong_bridge', 'unsupported_route'].includes(failure.message)
              ? failure.message : 'request_failed');
          }
        } finally {
          if (!controller.signal.aborted) setBusy(false);
        }
      }

      async function refresh(signal, pairingId) {
        const data = await api(signal, undefined, pairingId);
        if (signal.aborted) return;
        setStatus(data);
      }

      useEffect(() => {
        const controller = new AbortController();
        lifetime.current = controller;
        run(signal => refresh(signal), controller);
        return () => controller.abort();
      }, []);

      const pairingId = pairing?.state === 'pending' ? pairing.id : undefined;
      const expiresAt = pairing?.expiresAt;
      useEffect(() => {
        if (busy) return;
        const controller = new AbortController();
        let timer;
        async function check() {
          let pairingState;
          try {
            const data = await api(controller.signal, undefined, pairingId);
            if (controller.signal.aborted) return;
            setStatus(data);
            pairingState = data.pairingState;
          } catch {
            if (controller.signal.aborted) return;
            setStatus(current => current && { ...current, state: 'request_failed', directUrl: '' });
          }
          if (pairingId && Date.now() >= expiresAt && pairingState !== 'consumed') pairingState = 'expired';
          if (pairingState === 'consumed' || pairingState === 'expired') {
            setPairing(current => current?.id === pairingId ? { ...current, state: pairingState, png: '' } : current);
          }
          timer = setTimeout(check, 2500);
        }
        timer = setTimeout(check, 2500);
        return () => { clearTimeout(timer); controller.abort(); };
      }, [busy, pairingId, expiresAt]);

      const settings = draft ?? status?.settings ?? { socketPath: '', adminSocket: '' };
      const dirty = status && (settings.socketPath !== status.settings.socketPath || settings.adminSocket !== status.settings.adminSocket);
      const field = (key, label, help, placeholder) => h('label', { style: { display: 'block', margin: '8px 0' } },
        t(label), h('input', { style: inputStyle, value: settings[key], placeholder, spellCheck: false,
          autoComplete: 'off', disabled: busy || !status,
          onChange: event => setDraft({ ...settings, [key]: event.target.value }) }),
        h('small', { style: { display: 'block', marginTop: 3, opacity: 0.8 } }, t(help)));
      const button = (label, props) => h('button', { style: buttonStyle, type: 'button', ...props }, t(label));
      const save = event => {
        event.preventDefault();
        return run(async signal => {
          await api(signal, { action: 'save', ...settings });
          if (signal.aborted) return;
          setDraft(null);
          setPairing(null);
          await refresh(signal);
        });
      };
      const issue = () => run(async signal => {
        const data = await api(signal, { action: 'pair' });
        if (!signal.aborted) {
          const { pairingId: id, png, expiresAt } = data;
          setPairing({ id, png, expiresAt: expiresAt * 1000, state: 'pending' });
        }
      });

      const ready = status?.state === 'ready';
      return h('div', { style: { display: 'grid', gap: 14, maxWidth: 660 } },
        h('section', { style: panelStyle },
          h('div', { style: { display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 12 } },
            h('h3', { style: headingStyle }, t('status')),
            button('refresh', { disabled: busy, onClick: () => run(signal => refresh(signal, pairing?.id)) })),
          h('div', { 'aria-live': 'polite', 'aria-busy': busy },
            h('div', null, t(status?.state ?? (busy ? 'loading' : 'request_failed'))),
            status?.directUrl && h('div', { style: { overflowWrap: 'anywhere', marginTop: 5 } },
              t('directUrl'), ' · ', status.directUrl)),
          error && h('div', { role: 'alert', style: { marginTop: 8 } }, t(error))),
        h('section', { style: panelStyle },
          h('h3', { style: headingStyle }, t('pairing')),
          ready
            ? button(pairing ? 'regenerate' : 'generate', { style: primaryStyle, disabled: busy || Boolean(dirty), onClick: issue })
            : h('div', null, t('pairingUnavailable')),
          ready && pairing?.state === 'pending' && h('div', { style: { marginTop: 12 } },
            h('div', null, t('scan')),
            h('img', { alt: t('qrAlt'), src: `data:image/png;base64,${pairing.png}`, width: 256, height: 256,
              style: { display: 'block', maxWidth: '100%', height: 'auto', margin: '8px 0', imageRendering: 'pixelated' } }),
            h('div', null, t('expiry'), ' · ', new Date(pairing.expiresAt).toLocaleTimeString())),
          pairing && pairing.state !== 'pending' && h('div', { role: 'status', style: { marginTop: 8 } }, t(pairing.state))),
        h('details', { style: panelStyle },
          h('summary', { style: { cursor: 'pointer', fontWeight: 600 } }, t('settings')),
          h('form', { onSubmit: save, style: { marginTop: 12 } },
            status && h('div', null, t(status.bridgeReady ? 'bridgeReady' : 'bridgeOffline')),
            field('socketPath', 'bridgeSocket', 'bridgeHint', status?.socketPath),
            field('adminSocket', 'adminSocket', 'adminHint', status?.adminSocket),
            h('div', { style: buttonsStyle }, button('save', { type: 'submit', disabled: busy || !dirty })))));
    }

    return {
      inject: ['slots', 'locale'],
      apply(ctx) {
        ctx.effect(() => ctx.locale.register(namespace, locales), 'Rivo Relay translations');
        ctx.slots.inject('plugins.bundle.config', () => ctx.slots.register({
          name: 'plugins.bundle.config', key: 'rivo-dsh-bridge', locale: namespace,
        }, SettingsPage));
      },
    };
  },
});
