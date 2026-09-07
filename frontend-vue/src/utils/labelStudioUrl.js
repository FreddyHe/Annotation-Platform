const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '0.0.0.0', '::1'])
const DEFAULT_PORT_MAP = {
  5001: '21731'
}

const isAbsoluteUrl = (url) => /^[a-zA-Z][a-zA-Z\d+\-.]*:/.test(url)

const isLocalHost = (hostname) => LOCAL_HOSTS.has(hostname)

const getConfiguredPublicOrigin = () => {
  const configured = import.meta.env.VITE_LABEL_STUDIO_PUBLIC_URL
  if (!configured) return null
  try {
    return new URL(configured).origin
  } catch (error) {
    return null
  }
}

const getMappedPort = (port) => {
  const configuredMap = import.meta.env.VITE_LABEL_STUDIO_PORT_MAP
  if (configuredMap) {
    const pairs = configuredMap.split(',').map(item => item.trim()).filter(Boolean)
    for (const pair of pairs) {
      const [localPort, publicPort] = pair.split(':').map(item => item?.trim())
      if (localPort === port && publicPort) return publicPort
    }
  }
  return DEFAULT_PORT_MAP[port] || null
}

export const normalizeLabelStudioUrl = (rawUrl) => {
  if (!rawUrl || typeof window === 'undefined') return rawUrl

  try {
    const publicOrigin = getConfiguredPublicOrigin()
    const currentLocation = window.location
    const currentIsPublic = currentLocation.hostname && !isLocalHost(currentLocation.hostname)
    const localOrigin = `${currentLocation.protocol}//${currentLocation.hostname || 'localhost'}:5001`
    const baseUrl = publicOrigin || (currentIsPublic
      ? `${currentLocation.protocol}//${currentLocation.hostname}:${getMappedPort('5001') || '5001'}`
      : localOrigin)
    const parsed = new URL(rawUrl, isAbsoluteUrl(rawUrl) ? undefined : baseUrl)

    if (publicOrigin && (isLocalHost(parsed.hostname) || parsed.port === '5001')) {
      const publicUrl = new URL(publicOrigin)
      parsed.protocol = publicUrl.protocol
      parsed.hostname = publicUrl.hostname
      parsed.port = publicUrl.port
      return parsed.toString()
    }

    if (currentIsPublic && (isLocalHost(parsed.hostname) || parsed.hostname === currentLocation.hostname)) {
      parsed.hostname = currentLocation.hostname
      const mappedPort = getMappedPort(parsed.port || '5001')
      if (mappedPort) parsed.port = mappedPort
    }

    if (!currentIsPublic && isLocalHost(parsed.hostname) && currentLocation.hostname) {
      parsed.hostname = currentLocation.hostname
    }

    return parsed.toString()
  } catch (error) {
    return rawUrl
  }
}

export const openLabelStudioPendingWindow = () => {
  const pendingWindow = window.open('', '_blank')
  if (!pendingWindow) return null

  pendingWindow.opener = null
  pendingWindow.document.title = '正在连接星目智能标注'
  pendingWindow.document.body.innerHTML = `
    <main style="min-height:100vh;display:grid;place-items:center;margin:0;background:#f4f7fb;color:#0f2747;font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;">
      <section style="width:min(420px,calc(100vw - 48px));padding:36px;text-align:center;background:#fff;border:1px solid #dce6f2;border-radius:18px;box-shadow:0 18px 50px rgba(26,65,112,.12);">
        <div style="width:42px;height:42px;margin:0 auto 18px;border:4px solid #dbeafe;border-top-color:#1677ff;border-radius:50%;animation:ls-spin .8s linear infinite;"></div>
        <h1 style="margin:0 0 10px;font-size:21px;">正在连接星目智能标注</h1>
        <p style="margin:0;color:#64748b;font-size:14px;line-height:1.7;">正在建立安全会话并打开对应的复核项目，请稍候。</p>
      </section>
      <style>@keyframes ls-spin{to{transform:rotate(360deg)}}</style>
    </main>`
  return pendingWindow
}

export const closeLabelStudioPendingWindow = (pendingWindow) => {
  if (pendingWindow && !pendingWindow.closed) {
    pendingWindow.close()
  }
}

export const openLabelStudioUrl = (rawUrl, pendingWindow = null) => {
  const loginUrl = normalizeLabelStudioUrl(rawUrl)
  if (loginUrl) {
    if (pendingWindow && !pendingWindow.closed) {
      pendingWindow.location.replace(loginUrl)
    } else {
      window.open(loginUrl, '_blank', 'noopener')
    }
  }
  return loginUrl
}
