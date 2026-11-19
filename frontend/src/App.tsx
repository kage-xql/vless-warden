import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import vlessDragonLogo from './assets/vless-dragon.png'
import { Area, AreaChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api, ApiError, AUTH_EXPIRED_EVENT, TOKEN_KEY } from './api'
import { bytes, clientLimit, dateTime, daysFromNow, gbToBytes, time } from './format'
import type {
  ClientMetric,
  ConfirmAction,
  MonitorUpdate,
  ProvisionPayload,
  Server,
  ServerMetric,
  VlessClient,
  VlessClientConfig,
  VlessClientCreation,
  VlessInbound,
} from './types'

const DEFAULT_PROVISION: ProvisionPayload = {
  listenPort: 443,
  dest: 'www.microsoft.com:443',
  serverNames: ['www.microsoft.com'],
  shortIds: [],
  fingerprint: 'chrome',
  flow: 'xtls-rprx-vision',
}

function isNotProvisioned(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

export default function App() {
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY) ?? '')
  const [selectedId, setSelectedId] = useState('')
  const [showServerForm, setShowServerForm] = useState(false)
  const [serverToEdit, setServerToEdit] = useState<Server | null>(null)
  const [showProvision, setShowProvision] = useState(false)
  const [showClientForm, setShowClientForm] = useState(false)
  const [editingClient, setEditingClient] = useState<VlessClient | null>(null)
  const [link, setLink] = useState<VlessClientCreation | null>(null)
  const [confirmAction, setConfirmAction] = useState<ConfirmAction | null>(null)
  const [focusedClientId, setFocusedClientId] = useState('')
  const [streamState, setStreamState] = useState<'connecting' | 'live' | 'paused'>('connecting')
  const [authError, setAuthError] = useState('')
  const [provisionError, setProvisionError] = useState('')
  const [deleteError, setDeleteError] = useState('')
  const qc = useQueryClient()

  useEffect(() => {
    const logout = () => {
      localStorage.removeItem(TOKEN_KEY)
      setToken('')
      qc.clear()
    }
    window.addEventListener(AUTH_EXPIRED_EVENT, logout)
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, logout)
  }, [qc])

  const serversQuery = useQuery({
    queryKey: ['servers', token],
    queryFn: () => api<Server[]>('/api/servers', token),
    enabled: !!token,
  })
  const servers = serversQuery.data ?? []
  const activeId = servers.some(server => server.id === selectedId) ? selectedId : (servers[0]?.id ?? '')
  const active = servers.find(server => server.id === activeId)

  const inboundQuery = useQuery({
    queryKey: ['inbound', token, activeId],
    queryFn: () => api<VlessInbound>(`/api/servers/${activeId}/vless`, token),
    enabled: !!token && !!activeId,
    retry: false,
  })
  const inbound = inboundQuery.data
  const inboundReady = inbound?.status === 'READY'

  const clientsQuery = useQuery({
    queryKey: ['clients', token, activeId],
    queryFn: () => api<VlessClient[]>(`/api/servers/${activeId}/vless/clients`, token),
    enabled: !!token && !!activeId && inboundQuery.isSuccess,
    refetchInterval: 60_000,
  })
  const clients = clientsQuery.data ?? []
  const focusedClient = clients.find(client => client.id === focusedClientId)

  const metricsQuery = useQuery({
    queryKey: ['server-metrics', token, activeId],
    queryFn: () => api<ServerMetric[]>(`/api/servers/${activeId}/metrics`, token),
    enabled: !!token && !!activeId,
    refetchInterval: 30_000,
  })
  const focusedMetrics = useQuery({
    queryKey: ['client-metrics', token, focusedClientId],
    queryFn: () => api<ClientMetric[]>(`/api/vless/clients/${focusedClientId}/metrics`, token),
    enabled: !!token && !!focusedClientId,
  })

  useEffect(() => {
    if (!token) return
    const controller = new AbortController()
    const pause = (ms: number) =>
      new Promise<void>(resolve => {
        const id = setTimeout(resolve, ms)
        controller.signal.addEventListener(
          'abort',
          () => {
            clearTimeout(id)
            resolve()
          },
          { once: true },
        )
      })

    const receive = async () => {
      while (!controller.signal.aborted) {
        try {
          setStreamState('connecting')
          const response = await fetch('/api/events', {
            headers: { Authorization: `Bearer ${token}` },
            signal: controller.signal,
          })
          if (response.status === 401) {
            window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT))
            break
          }
          if (!response.ok || !response.body) throw new Error(`SSE HTTP ${response.status}`)
          setStreamState('live')
          const reader = response.body.getReader()
          const decoder = new TextDecoder()
          let buffer = ''
          while (!controller.signal.aborted) {
            const part = await reader.read()
            if (part.done) break
            buffer += decoder.decode(part.value, { stream: true })
            let boundary: number
            while ((boundary = buffer.indexOf('\n\n')) >= 0) {
              const raw = buffer.slice(0, boundary)
              buffer = buffer.slice(boundary + 2)
              const line = raw.split('\n').find(value => value.startsWith('data:'))
              if (!line) continue
              const update = JSON.parse(line.slice(5).trim()) as MonitorUpdate
              qc.setQueryData<Server[]>(['servers', token], old =>
                old?.map(server =>
                  server.id === update.serverId
                    ? { ...server, status: update.status, connectionError: update.connectionError }
                    : server,
                ),
              )
              if (update.server) {
                qc.setQueryData<ServerMetric[]>(['server-metrics', token, update.serverId], old =>
                  [...(old ?? []), update.server!].slice(-1440),
                )
              }
              for (const item of update.clients) {
                if (item.metrics) {
                  qc.setQueryData<ClientMetric[]>(['client-metrics', token, item.clientId], old =>
                    [...(old ?? []), item.metrics!].slice(-1440),
                  )
                }
                qc.setQueryData<VlessClient[]>(['clients', token, update.serverId], old =>
                  old?.map(client => (client.id === item.clientId ? { ...client, enabled: item.enabled } : client)),
                )
              }
              void qc.invalidateQueries({ queryKey: ['clients', token, update.serverId] })
            }
          }
          if (!controller.signal.aborted) throw new Error('SSE stream ended')
        } catch {
          if (controller.signal.aborted) break
          setStreamState('paused')
          await pause(2500)
        }
      }
    }
    void receive()
    return () => controller.abort()
  }, [token, qc])

  const chartData = useMemo(
    () => (metricsQuery.data ?? []).map(metric => ({ ...metric, time: time(metric.timestamp) })),
    [metricsQuery.data],
  )
  const latest = chartData.at(-1)

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api<void>(`/api/servers/${id}`, token, { method: 'DELETE' }),
    onSuccess: (_, id) => {
      setDeleteError('')
      setSelectedId(current => (current === id ? '' : current))
      qc.removeQueries({ queryKey: ['server-metrics', token, id] })
      qc.removeQueries({ queryKey: ['inbound', token, id] })
      qc.removeQueries({ queryKey: ['clients', token, id] })
      void serversQuery.refetch()
    },
    onError: error => setDeleteError(error.message),
  })

  if (!token) {
    return (
      <AuthScreen
        onSuccess={value => {
          localStorage.setItem(TOKEN_KEY, value)
          setToken(value)
        }}
        error={authError}
        setError={setAuthError}
      />
    )
  }

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-glyph"><img className="wireguard-logo" src={vlessDragonLogo} alt="vless-warden"/></span>
          <div>
            <strong>vless-warden</strong>
            <small>XRAY · REALITY CONTROL</small>
          </div>
        </div>
        <div className="nav-heading">
          SERVERS <button className="square" aria-label="Добавить сервер" onClick={() => setShowServerForm(true)}>+</button>
        </div>
        {serversQuery.isLoading && <div className="side-message">Загружаю список серверов…</div>}
        {serversQuery.isError && (
          <div className="side-error">
            {serversQuery.error.message}
            <button onClick={() => void serversQuery.refetch()}>Повторить</button>
          </div>
        )}
        {!serversQuery.isLoading && serversQuery.isSuccess && servers.length === 0 && (
          <div className="side-message">Серверов пока нет.</div>
        )}
        <nav className="server-list">
          {servers.map(server => (
            <button
              key={server.id}
              className={`server-nav ${server.id === activeId ? 'active' : ''}`}
              onClick={() => {
                setSelectedId(server.id)
                setFocusedClientId('')
                setShowClientForm(false)
                setProvisionError('')
              }}
            >
              <StatusMark status={server.status} />
              <span>
                <strong>{server.name}</strong>
                <small>{server.host}</small>
              </span>
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <span className={`stream-state ${streamState}`}>
            <i />
            {streamState === 'live' ? 'LIVE · SSE' : streamState === 'paused' ? 'SSE PAUSED' : 'CONNECTING'}
          </span>
          <button
            className="logout"
            onClick={() => {
              localStorage.removeItem(TOKEN_KEY)
              setToken('')
              qc.clear()
            }}
          >
            Sign out ↗
          </button>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <div className="crumb">VLESS / <b>{active?.name ?? 'OVERVIEW'}</b></div>
          <div className="topbar-right">
            <span className={`stream-state ${streamState}`}>
              <i />
              {streamState === 'live' ? 'LIVE' : streamState === 'paused' ? 'UPDATES PAUSED' : 'CONNECTING'}
            </span>
            <span className="user-chip">
              {servers.length} SERVER{servers.length === 1 ? '' : 'S'}
            </span>
          </div>
        </header>

        {!active && (
          <section className="welcome panel">
            <div className="eyebrow">VLESS / REALITY CONTROL PLANE</div>
            <h1>
              {serversQuery.isLoading
                ? 'Loading your workspace'
                : serversQuery.isError
                  ? 'Could not load servers'
                  : 'No servers connected'}
            </h1>
            <p>
              {serversQuery.isLoading
                ? 'Fetching the latest server list…'
                : serversQuery.isError
                  ? serversQuery.error.message
                  : 'Add a VPS to provision Xray with VLESS/REALITY and manage client links.'}
            </p>
            {serversQuery.isError ? (
              <button className="button" onClick={() => void serversQuery.refetch()}>Retry request</button>
            ) : (
              !serversQuery.isLoading && <button className="button" onClick={() => setShowServerForm(true)}>＋ Add server</button>
            )}
          </section>
        )}

        {active && (
          <>
            <section className="server-heading">
              <div>
                <div className="eyebrow">
                  SERVER OVERVIEW <span className="slash">/</span> {active.operatingSystem ?? 'OS UNKNOWN'}
                </div>
                <h1>{active.name}</h1>
                <p className="hostline">
                  {active.host}:{active.sshPort} <span>·</span> {active.sshUser}
                </p>
              </div>
              <div className="heading-actions">
                <button className="button" onClick={() => setServerToEdit(active)}>Edit SSH access</button>
                <button
                  className="button danger"
                  disabled={deleteMutation.isPending}
                  onClick={() =>
                    setConfirmAction({
                      title: 'Delete server?',
                      message:
                        `${active.name}, its VLESS clients and metrics will be removed. vless-warden will also stop its own Xray unit and delete only its own files and firewall rule on the VPS. If remote cleanup fails, the server is kept so access is not silently left active.`,
                      onConfirm: () => deleteMutation.mutate(active.id),
                    })
                  }
                >
                  {deleteMutation.isPending ? 'Deleting…' : 'Delete server'}
                </button>
                <button
                  className="button primary"
                  disabled={!active.hostKeyFingerprint}
                  onClick={() => {
                    setProvisionError('')
                    setShowProvision(true)
                  }}
                >
                  {inbound ? 'Reconfigure VLESS' : 'Provision VLESS'}
                </button>
                <div className={`status-badge ${active.status.toLowerCase()}`}>
                  <StatusMark status={active.status} />
                  {active.status}
                </div>
              </div>
            </section>

            {provisionError && <ErrorAlert title="VLESS provisioning failed" message={provisionError} onDismiss={() => setProvisionError('')} />}
            {deleteError && <ErrorAlert title="Server was not deleted" message={deleteError} onDismiss={() => setDeleteError('')} />}
            {active.status === 'ERROR' && (
              <div className="alert error">
                <span>!</span>
                <div>
                  <strong>Server connection failed</strong>
                  <p>{active.connectionError || 'SSH connection unavailable. Check the host and credentials.'}</p>
                </div>
                <button onClick={() => void serversQuery.refetch()}>Refresh status</button>
              </div>
            )}

            {inboundQuery.isError && !isNotProvisioned(inboundQuery.error) && (
              <ErrorAlert title="Could not load VLESS inbound" message={inboundQuery.error.message} onRetry={() => void inboundQuery.refetch()} />
            )}

            {!inboundQuery.isLoading && !inbound && (
              <section className="panel welcome">
                <div className="eyebrow">XRAY / NOT PROVISIONED</div>
                <h1>VLESS/REALITY is not configured</h1>
                <p>
                  Provision Xray on this server. vless-warden installs its own binary, config and systemd unit under
                  /opt/vless-warden and /etc/vless-warden, without touching a system-wide Xray installation or WireGuard.
                </p>
                <button className="button primary" disabled={!active.hostKeyFingerprint} onClick={() => setShowProvision(true)}>
                  Provision VLESS
                </button>
              </section>
            )}

            {inbound && (
              <>
                {inbound.status === 'ERROR' && (
                  <div className="alert error">
                    <span>!</span>
                    <div>
                      <strong>VLESS inbound needs attention</strong>
                      <p>{inbound.lastError || 'The last provisioning attempt failed.'}</p>
                    </div>
                    <button onClick={() => setShowProvision(true)}>Reconfigure</button>
                  </div>
                )}
                {!inboundReady && inbound.status !== 'ERROR' && (
                  <div className="alert">
                    <span>i</span>
                    <div>
                      <strong>Inbound is not ready</strong>
                      <p>Client management becomes available when the inbound reaches READY.</p>
                    </div>
                  </div>
                )}

                <div className="stats-grid">
                  <Stat title="CPU LOAD" value={latest ? `${latest.cpu.toFixed(1)}%` : '—'} detail="1m system load" tone="green" />
                  <Stat title="MEMORY" value={latest ? `${latest.ram.toFixed(1)}%` : '—'} detail="used RAM" tone="blue" />
                  <Stat title="ROOT DISK" value={latest ? `${latest.disk.toFixed(1)}%` : '—'} detail="used filesystem" tone="amber" />
                  <Stat title="CLIENTS" value={`${clients.filter(client => client.enabled).length}`} detail={`${clients.length} configured`} tone="purple" />
                </div>

                <section className="charts-grid">
                  <article className="panel chart-panel">
                    <PanelTitle title="System resources" subtitle="Rolling history · last 24 hours" />
                    <ChartState loading={metricsQuery.isLoading} empty={!chartData.length} error={metricsQuery.isError} />
                    {!metricsQuery.isLoading && !metricsQuery.isError && chartData.length > 0 && (
                      <div className="chart">
                        <ResponsiveContainer width="100%" height="100%">
                          <AreaChart data={chartData}>
                            <defs>
                              <linearGradient id="cpu" x1="0" y1="0" x2="0" y2="1">
                                <stop offset="0%" stopColor="#a3e635" stopOpacity={0.2} />
                                <stop offset="100%" stopColor="#a3e635" stopOpacity={0} />
                              </linearGradient>
                            </defs>
                            <CartesianGrid stroke="#292c29" vertical={false} />
                            <XAxis dataKey="time" tickLine={false} axisLine={false} tick={{ fill: '#777d73', fontSize: 10 }} minTickGap={35} />
                            <YAxis domain={[0, 100]} unit="%" tickLine={false} axisLine={false} tick={{ fill: '#777d73', fontSize: 10 }} />
                            <Tooltip contentStyle={{ background: '#181a18', border: '1px solid #3a4037', borderRadius: 4, fontFamily: 'monospace' }} />
                            <Area name="CPU load" dataKey="cpu" type="monotone" stroke="#a3e635" fill="url(#cpu)" strokeWidth={1.8} />
                            <Line name="RAM" dataKey="ram" type="monotone" stroke="#60a5fa" strokeWidth={1.7} dot={false} />
                            <Line name="Disk" dataKey="disk" type="monotone" stroke="#fbbf24" strokeWidth={1.5} dot={false} />
                          </AreaChart>
                        </ResponsiveContainer>
                      </div>
                    )}
                    <div className="chart-legend">
                      <span className="green">● CPU</span>
                      <span className="blue">● RAM</span>
                      <span className="amber">● DISK</span>
                    </div>
                  </article>
                  <article className="panel chart-panel">
                    <PanelTitle title="Network traffic" subtitle="Cumulative host interface bytes" />
                    <ChartState loading={metricsQuery.isLoading} empty={!chartData.length} error={metricsQuery.isError} />
                    {!metricsQuery.isLoading && !metricsQuery.isError && chartData.length > 0 && (
                      <div className="chart">
                        <ResponsiveContainer width="100%" height="100%">
                          <LineChart data={chartData}>
                            <CartesianGrid stroke="#292c29" vertical={false} />
                            <XAxis dataKey="time" tickLine={false} axisLine={false} tick={{ fill: '#777d73', fontSize: 10 }} minTickGap={35} />
                            <YAxis tickLine={false} axisLine={false} tick={{ fill: '#777d73', fontSize: 10 }} tickFormatter={value => bytes(Number(value))} />
                            <Tooltip contentStyle={{ background: '#181a18', border: '1px solid #3a4037', borderRadius: 4, fontFamily: 'monospace' }} formatter={value => bytes(Number(value))} />
                            <Line name="RX" dataKey="netRx" type="monotone" stroke="#a3e635" strokeWidth={1.8} dot={false} />
                            <Line name="TX" dataKey="netTx" type="monotone" stroke="#60a5fa" strokeWidth={1.8} dot={false} />
                          </LineChart>
                        </ResponsiveContainer>
                      </div>
                    )}
                    <div className="chart-legend">
                      <span className="green">↓ {bytes(latest?.netRx ?? 0)}</span>
                      <span className="blue">↑ {bytes(latest?.netTx ?? 0)}</span>
                    </div>
                  </article>
                </section>

                {focusedClient && (
                  <ClientChart
                    client={focusedClient}
                    data={focusedMetrics.data ?? []}
                    loading={focusedMetrics.isLoading}
                    error={focusedMetrics.isError}
                  />
                )}

                <section className="panel peer-panel">
                  <div className="panel-header">
                    <PanelTitle title="VLESS clients" subtitle="Links, limits and Xray traffic counters" />
                    <div className="panel-actions">
                      <span className="status-badge">{inbound.dest} · :{inbound.listenPort} · {inbound.fingerprint}</span>
                      <button className="button" onClick={() => setShowClientForm(show => !show)} disabled={!inboundReady}>
                        ＋ Add client
                      </button>
                    </div>
                  </div>
                  {!inboundReady && <div className="inline-note">Client actions are unavailable while the inbound is not ready.</div>}
                  {clientsQuery.isLoading ? (
                    <div className="state-box">Loading client list…</div>
                  ) : clientsQuery.isError ? (
                    <div className="state-box error-text">{clientsQuery.error.message}</div>
                  ) : clients.length === 0 ? (
                    <div className="state-box">No clients yet. Create one to get a vless:// link.</div>
                  ) : (
                    <div className="table-scroll">
                      <table>
                        <thead>
                          <tr>
                            <th>NAME</th>
                            <th>STATE</th>
                            <th>TRAFFIC</th>
                            <th>LIMIT / EXPIRY</th>
                            <th>RX / TX</th>
                            <th />
                          </tr>
                        </thead>
                        <tbody>
                          {clients.map(client => (
                            <ClientRow
                              key={client.id}
                              token={token}
                              client={client}
                              selected={focusedClientId === client.id}
                              disabled={!inboundReady}
                              onSelect={() => setFocusedClientId(client.id)}
                              onEdit={() => setEditingClient(client)}
                              onLink={result => setLink(result)}
                              onChanged={() => void clientsQuery.refetch()}
                              onRequestDelete={action => setConfirmAction(action)}
                            />
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                  {showClientForm && inboundReady && (
                    <ClientForm
                      token={token}
                      serverId={active.id}
                      onCreated={created => {
                        setLink(created)
                        setShowClientForm(false)
                        void clientsQuery.refetch()
                      }}
                    />
                  )}
                </section>
              </>
            )}
          </>
        )}
      </main>

      {showServerForm && (
        <ServerDialog
          token={token}
          onClose={() => setShowServerForm(false)}
          onSaved={server => {
            setSelectedId(server.id)
            void serversQuery.refetch()
          }}
        />
      )}
      {serverToEdit && (
        <ServerDialog
          token={token}
          server={serverToEdit}
          onClose={() => setServerToEdit(null)}
          onSaved={server => {
            setSelectedId(server.id)
            void serversQuery.refetch()
          }}
        />
      )}
      {showProvision && active && (
        <ProvisionDialog
          token={token}
          serverId={active.id}
          inbound={inbound}
          onClose={() => setShowProvision(false)}
          onProvisioned={() => {
            setShowProvision(false)
            setProvisionError('')
            void inboundQuery.refetch()
            void clientsQuery.refetch()
          }}
          onError={setProvisionError}
        />
      )}
      {link && <LinkDialog link={link} onClose={() => setLink(null)} />}
      {editingClient && (
        <ClientEditDialog
          token={token}
          client={editingClient}
          onClose={() => setEditingClient(null)}
          onSaved={result => {
            setEditingClient(null)
            if (result) setLink(result)
            void clientsQuery.refetch()
          }}
        />
      )}
      {confirmAction && (
        <ConfirmDialog
          action={confirmAction}
          onClose={() => setConfirmAction(null)}
          onConfirm={() => {
            confirmAction.onConfirm()
            setConfirmAction(null)
          }}
        />
      )}
    </div>
  )
}

function StatusMark({ status }: { status: Server['status'] }) {
  return <i className={`status-mark ${status.toLowerCase()}`} aria-label={status} />
}

function Stat({ title, value, detail, tone }: { title: string; value: string; detail: string; tone: string }) {
  return (
    <article className="stat panel">
      <div className="eyebrow"><i className={tone} /> {title}</div>
      <strong>{value}</strong>
      <small>{detail}</small>
    </article>
  )
}

function PanelTitle({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <div className="panel-title">
      <h2>{title}</h2>
      <p>{subtitle}</p>
    </div>
  )
}

function ChartState({ loading, empty, error }: { loading: boolean; empty: boolean; error: boolean }) {
  if (loading) return <div className="chart-state">Loading metrics…</div>
  if (error) return <div className="chart-state error-text">Metrics could not be loaded.</div>
  if (empty) return <div className="chart-state">No history yet. Metrics appear after the first successful poll.</div>
  return null
}

function ErrorAlert({ title, message, onDismiss, onRetry }: { title: string; message: string; onDismiss?: () => void; onRetry?: () => void }) {
  return (
    <div className="alert error">
      <span>!</span>
      <div><strong>{title}</strong><p>{message}</p></div>
      {onRetry && <button onClick={onRetry}>Retry</button>}
      {onDismiss && <button onClick={onDismiss}>Dismiss</button>}
    </div>
  )
}

function AuthScreen({ onSuccess, error, setError }: { onSuccess: (token: string) => void; error: string; setError: (value: string) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [setupToken, setSetupToken] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      const result = await api<{ token: string }>(`/api/auth/${mode}`, '', {
        method: 'POST',
        body: JSON.stringify({ email, password, setupToken: setupToken || undefined }),
      })
      onSuccess(result.token)
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Authentication failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="auth-wrap">
      <section className="auth-card panel">
        <div className="brand auth-brand">
          <span className="brand-glyph"><img className="wireguard-logo" src={vlessDragonLogo} alt="vless-warden"/></span>
          <div><strong>vless-warden</strong><small>XRAY · REALITY CONTROL</small></div>
        </div>
        <div className="eyebrow">AUTH / {mode.toUpperCase()}</div>
        <h1>{mode === 'login' ? 'Sign in' : 'Create account'}</h1>
        <p>Control plane for Xray VLESS/REALITY servers.</p>
        <form onSubmit={submit}>
          <label>Email<input type="email" required autoComplete="email" value={email} onChange={event => setEmail(event.target.value)} /></label>
          <label>Password<input type="password" required minLength={8} autoComplete={mode === 'login' ? 'current-password' : 'new-password'} value={password} onChange={event => setPassword(event.target.value)} /></label>
          {mode === 'register' && (
            <label>
              Setup token
              <input type="password" autoComplete="off" value={setupToken} onChange={event => setSetupToken(event.target.value)} placeholder="Required only for the first account" />
              <small className="form-hint">Printed by install.sh or stored as APP_BOOTSTRAP_TOKEN.</small>
            </label>
          )}
          {error && <div className="form-error">{error}</div>}
          <button className="button primary wide" disabled={busy}>{busy ? 'Checking credentials…' : mode === 'login' ? 'Continue' : 'Register'}</button>
        </form>
        <button className="link-button" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError('') }}>
          {mode === 'login' ? 'Create an account' : 'Back to sign in'}
        </button>
      </section>
    </main>
  )
}

function ServerDialog({ token, server, onClose, onSaved }: { token: string; server?: Server; onClose: () => void; onSaved: (server: Server) => void }) {
  const [name, setName] = useState(server?.name ?? '')
  const [host, setHost] = useState(server?.host ?? '')
  const [sshPort, setSshPort] = useState(String(server?.sshPort ?? 22))
  const [sshUser, setSshUser] = useState(server?.sshUser ?? '')
  const [password, setPassword] = useState('')
  const [privateKey, setPrivateKey] = useState('')
  const [passphrase, setPassphrase] = useState('')
  const [trustNewHostKey, setTrustNewHostKey] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<Server | null>(null)
  const editing = Boolean(server)
  const credentialsProvided = Boolean(password.trim()) || Boolean(privateKey.trim())
  const secretValid = editing ? !credentialsProvided || Boolean(password.trim()) !== Boolean(privateKey.trim()) : Boolean(password.trim()) !== Boolean(privateKey.trim())

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!secretValid) {
      setError('Enter exactly one credential: a password or a private key.')
      return
    }
    setBusy(true)
    setError('')
    try {
      const credentials = credentialsProvided
        ? { password: password || undefined, privateKey: privateKey || undefined, passphrase: passphrase || undefined }
        : undefined
      const payload = JSON.stringify({ name, host, sshPort: Number(sshPort), sshUser, credentials, trustNewHostKey: trustNewHostKey || undefined })
      const saved = await api<Server>(server ? `/api/servers/${server.id}` : '/api/servers', token, {
        method: server ? 'PUT' : 'POST',
        body: payload,
      })
      setPassword('')
      setPrivateKey('')
      setPassphrase('')
      setResult(saved)
      onSaved(saved)
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'SSH check failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="overlay" onMouseDown={event => event.target === event.currentTarget && onClose()}>
      <section className="dialog panel">
        <button className="close" onClick={onClose}>×</button>
        {!result ? (
          <>
            <div className="eyebrow">CONNECTION / SSH</div>
            <h2>{editing ? 'Repair server connection' : 'Add server'}</h2>
            <p className="dialog-copy">
              {editing
                ? 'Saved SSH credentials stay encrypted and are reused if you leave both credential fields empty. Enter new values only to replace them. Leave “Trust a new host key” off unless the server was reinstalled.'
                : 'Connect to Ubuntu or Debian. The first successful SSH connection pins its host key.'}
            </p>
            <form onSubmit={submit} className="form-stack">
              <label>Server name<input required maxLength={100} value={name} onChange={event => setName(event.target.value)} placeholder="vless-node-1" /></label>
              <label>Address<input required maxLength={255} value={host} onChange={event => setHost(event.target.value)} placeholder="vpn.example.net" /></label>
              <div className="field-row">
                <label>SSH port<input type="number" min="1" max="65535" required value={sshPort} onChange={event => setSshPort(event.target.value)} /></label>
                <label>SSH user<input required value={sshUser} onChange={event => setSshUser(event.target.value)} placeholder="root" /></label>
              </div>
              <label>Password<input type="password" autoComplete="new-password" value={password} onChange={event => { setPassword(event.target.value); if (event.target.value) setPrivateKey('') }} placeholder="Enter SSH password" /></label>
              <label>Or private key<textarea rows={4} autoComplete="off" spellCheck={false} value={privateKey} onChange={event => { setPrivateKey(event.target.value); if (event.target.value) setPassword('') }} placeholder="-----BEGIN OPENSSH PRIVATE KEY-----" /></label>
              {privateKey && <label>Key passphrase<input type="password" autoComplete="off" value={passphrase} onChange={event => setPassphrase(event.target.value)} /></label>}
              {editing && (
                <label className="checkbox-label">
                  <input type="checkbox" checked={trustNewHostKey} onChange={event => setTrustNewHostKey(event.target.checked)} /> Trust a new SSH host key (server was reinstalled)
                </label>
              )}
              <p className="form-hint">
                {editing
                  ? 'Leave both credential fields empty to reuse the saved encrypted credentials, or enter exactly one new credential.'
                  : 'Enter exactly one SSH credential. It will be encrypted using the current application key.'}
              </p>
              {error && <div className="form-error">{error}</div>}
              <div className="dialog-actions">
                <button type="button" className="button quiet" onClick={onClose}>Cancel</button>
                <button className="button primary" disabled={busy}>{busy ? <><span className="spinner" /> Checking SSH…</> : editing ? 'Save and reconnect' : 'Check & add server'}</button>
              </div>
            </form>
          </>
        ) : (
          <>
            <div className="eyebrow">SSH CHECK COMPLETE</div>
            <h2>{result.status === 'ONLINE' ? 'Server connected' : 'Could not connect'}</h2>
            <div className={`result-panel ${result.status.toLowerCase()}`}>
              <StatusMark status={result.status} />
              <div>
                <strong>{result.status}</strong>
                <p>{result.status === 'ONLINE' ? `Detected OS: ${result.operatingSystem ?? 'unknown'}` : result.connectionError ?? 'SSH connection failed. Verify the host, credentials and host key.'}</p>
                {result.status === 'ONLINE' && result.hostKeyFingerprint && <small className="mono">Host key {result.hostKeyFingerprint}</small>}
              </div>
            </div>
            <div className="dialog-actions"><button className="button primary" onClick={onClose}>Done</button></div>
          </>
        )}
      </section>
    </div>
  )
}

function ProvisionDialog({ token, serverId, inbound, onClose, onProvisioned, onError }: {
  token: string
  serverId: string
  inbound: VlessInbound | undefined
  onClose: () => void
  onProvisioned: () => void
  onError: (message: string) => void
}) {
  const [listenPort, setListenPort] = useState(String(inbound?.listenPort ?? DEFAULT_PROVISION.listenPort))
  const [dest, setDest] = useState(inbound?.dest ?? DEFAULT_PROVISION.dest)
  const [serverNames, setServerNames] = useState(inbound?.serverNames ?? DEFAULT_PROVISION.serverNames.join(', '))
  const [shortIds, setShortIds] = useState(inbound?.shortIds ?? '')
  const [fingerprint, setFingerprint] = useState(inbound?.fingerprint ?? DEFAULT_PROVISION.fingerprint)
  const [flow, setFlow] = useState(inbound?.flow ?? DEFAULT_PROVISION.flow)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      const payload: ProvisionPayload = {
        listenPort: Number(listenPort),
        dest: dest.trim(),
        serverNames: serverNames.split(',').map(value => value.trim()).filter(Boolean),
        shortIds: shortIds.split(',').map(value => value.trim()).filter(Boolean),
        fingerprint,
        flow,
      }
      await api<VlessInbound>(`/api/servers/${serverId}/provisioning/vless`, token, {
        method: 'POST',
        body: JSON.stringify(payload),
      })
      onProvisioned()
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : 'Provisioning failed'
      setError(message)
      onError(message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="overlay" onMouseDown={event => event.target === event.currentTarget && onClose()}>
      <section className="dialog panel">
        <button className="close" onClick={onClose}>×</button>
        <div className="eyebrow">XRAY / REALITY</div>
        <h2>{inbound ? 'Reconfigure VLESS inbound' : 'Provision VLESS inbound'}</h2>
        <p className="dialog-copy">
          vless-warden installs pinned Xray {inbound?.xrayVersion ?? 'v26.11.9'} under /opt/vless-warden and manages only
          the vless-warden systemd unit. REALITY keys are preserved when reconfiguring, so existing client links keep working.
        </p>
        <form onSubmit={submit} className="form-stack">
          <div className="field-row">
            <label>Listen port<input type="number" min="1" max="65535" required value={listenPort} onChange={event => setListenPort(event.target.value)} /></label>
            <label>REALITY dest<input required value={dest} onChange={event => setDest(event.target.value)} placeholder="www.microsoft.com:443" /></label>
          </div>
          <label>Server names (SNI)<input required value={serverNames} onChange={event => setServerNames(event.target.value)} placeholder="www.microsoft.com" /><small className="form-hint">Comma-separated. The first value is used in vless:// links.</small></label>
          <label>Short IDs<input value={shortIds} onChange={event => setShortIds(event.target.value)} placeholder="Leave empty to generate one" /><small className="form-hint">Even-length hex up to 16 characters, comma-separated.</small></label>
          <div className="field-row">
            <label>Fingerprint
              <select value={fingerprint} onChange={event => setFingerprint(event.target.value)}>
                {['chrome', 'firefox', 'safari', 'ios', 'android', 'edge', 'random', 'randomized'].map(value => <option key={value} value={value}>{value}</option>)}
              </select>
            </label>
            <label>Flow
              <select value={flow} onChange={event => setFlow(event.target.value)}>
                <option value="xtls-rprx-vision">xtls-rprx-vision</option>
                <option value="">none</option>
              </select>
            </label>
          </div>
          {error && <div className="form-error">{error}</div>}
          <div className="dialog-actions">
            <button type="button" className="button quiet" onClick={onClose}>Cancel</button>
            <button className="button primary" disabled={busy}>{busy ? <><span className="spinner" /> Provisioning…</> : inbound ? 'Apply configuration' : 'Install Xray & provision'}</button>
          </div>
        </form>
      </section>
    </div>
  )
}

function ClientForm({ token, serverId, onCreated }: { token: string; serverId: string; onCreated: (created: VlessClientCreation) => void }) {
  const [name, setName] = useState('')
  const [trafficGb, setTrafficGb] = useState('')
  const [durationDays, setDurationDays] = useState('')
  const [showLimits, setShowLimits] = useState(false)
  const [error, setError] = useState('')
  const mutation = useMutation({
    mutationFn: () =>
      api<VlessClientCreation>(`/api/servers/${serverId}/vless/clients`, token, {
        method: 'POST',
        body: JSON.stringify({
          name,
          trafficLimitBytes: gbToBytes(trafficGb),
          expiresAt: daysFromNow(durationDays),
        }),
      }),
    onSuccess: onCreated,
    onError: caught => setError(caught.message),
  })

  return (
    <>
      <form className="peer-form" onSubmit={event => { event.preventDefault(); setError(''); mutation.mutate() }}>
        <label>Client name<input autoFocus required maxLength={100} value={name} onChange={event => setName(event.target.value)} placeholder="e.g. Laptop" /></label>
        <div className="peer-form-actions">
          <button type="button" className={`button quiet ${trafficGb || durationDays ? 'limits-active' : ''}`} onClick={() => setShowLimits(true)}>
            ＋ {trafficGb || durationDays ? 'Edit limits' : 'Add limits'}
          </button>
          <button className="button primary" disabled={mutation.isPending || !name.trim()}>
            {mutation.isPending ? <><span className="spinner" /> Adding client…</> : 'Create vless:// link'}
          </button>
        </div>
        {error && <div className="form-error">{error}</div>}
      </form>
      {showLimits && (
        <LimitsDialog
          trafficGb={trafficGb}
          durationDays={durationDays}
          onClose={() => setShowLimits(false)}
          onSave={(traffic, days) => {
            setTrafficGb(traffic)
            setDurationDays(days)
            setShowLimits(false)
          }}
        />
      )}
    </>
  )
}

function LimitsDialog({ trafficGb, durationDays, onClose, onSave }: { trafficGb: string; durationDays: string; onClose: () => void; onSave: (traffic: string, days: string) => void }) {
  const [traffic, setTraffic] = useState(trafficGb)
  const [days, setDays] = useState(durationDays)
  return (
    <div className="overlay" onMouseDown={event => event.target === event.currentTarget && onClose()}>
      <section className="dialog panel limits-dialog">
        <button className="close" onClick={onClose}>×</button>
        <div className="eyebrow">CLIENT / LIMITS</div>
        <h2>Configure client limits</h2>
        <p className="dialog-copy">Limits are enforced by monitoring. Leave a field empty for unlimited usage.</p>
        <div className="form-stack">
          <label>Traffic limit (GB)<input type="number" step="0.1" min="0" value={traffic} onChange={event => setTraffic(event.target.value)} placeholder="Unlimited" /></label>
          <label>Usage limit (days)<input type="number" step="1" min="0" value={days} onChange={event => setDays(event.target.value)} placeholder="Unlimited" /></label>
        </div>
        <div className="dialog-actions">
          <button className="button quiet" onClick={onClose}>Cancel</button>
          <button className="button primary" onClick={() => onSave(traffic, days)}>Apply limits</button>
        </div>
      </section>
    </div>
  )
}

function ClientEditDialog({ token, client, onClose, onSaved }: { token: string; client: VlessClient; onClose: () => void; onSaved: (result: VlessClientCreation | null) => void }) {
  const [name, setName] = useState(client.name)
  const [trafficGb, setTrafficGb] = useState(client.trafficLimitBytes ? String(Math.round((client.trafficLimitBytes / (1024 * 1024 * 1024)) * 10) / 10) : '')
  const [durationDays, setDurationDays] = useState(() => {
    if (!client.expiresAt) return ''
    const difference = new Date(client.expiresAt).getTime() - Date.now()
    return difference > 0 ? String(Math.ceil(difference / 86_400_000)) : ''
  })
  const [enabled, setEnabled] = useState(client.enabled)
  const [rotateUuid, setRotateUuid] = useState(false)
  const [error, setError] = useState('')
  const save = useMutation({
    mutationFn: () => {
      const gb = gbToBytes(trafficGb)
      return api<VlessClientCreation>(`/api/vless/clients/${client.id}`, token, {
        method: 'PUT',
        body: JSON.stringify({
          name: name.trim(),
          enabled,
          trafficLimitBytes: gb,
          clearTrafficLimit: gb === null,
          expiresAt: daysFromNow(durationDays),
          clearExpiresAt: !durationDays.trim() || daysFromNow(durationDays) === null,
          rotateUuid,
        }),
      })
    },
    onSuccess: result => onSaved(result),
    onError: caught => setError(caught.message),
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!name.trim()) {
      setError('Client name cannot be blank')
      return
    }
    setError('')
    save.mutate()
  }

  return (
    <div className="overlay" onMouseDown={event => event.target === event.currentTarget && onClose()}>
      <section className="dialog panel">
        <button className="close" onClick={onClose}>×</button>
        <div className="eyebrow">CLIENT / EDIT</div>
        <h2>Edit {client.name}</h2>
        <p className="dialog-copy">
          Usage: {bytes(client.trafficUsedBytes)}{clientLimit(client) ? ` · ${clientLimit(client)}` : ''}. When limits are reached the client is disabled on the Xray server.
        </p>
        <form onSubmit={submit} className="form-stack">
          <label>Client name<input value={name} onChange={event => setName(event.target.value)} maxLength={100} required /></label>
          <div className="field-row">
            <label>Traffic limit (GB)<input type="number" step="0.1" min="0" value={trafficGb} onChange={event => setTrafficGb(event.target.value)} placeholder="Unlimited" /></label>
            <label>Usage limit (days)<input type="number" step="1" min="0" value={durationDays} onChange={event => { setDurationDays(event.target.value); if (!enabled && event.target.value) setEnabled(true) }} placeholder="Unlimited" /></label>
          </div>
          <label className="checkbox-label"><input type="checkbox" checked={enabled} onChange={event => setEnabled(event.target.checked)} /> Enable client (keeps it in the Xray config)</label>
          <label className="checkbox-label"><input type="checkbox" checked={rotateUuid} onChange={event => setRotateUuid(event.target.checked)} /> Rotate UUID (old vless:// link stops working)</label>
          {error && <div className="form-error">{error}</div>}
          <div className="dialog-actions">
            <button type="button" className="button quiet" onClick={onClose}>Cancel</button>
            <button className="button primary" disabled={save.isPending}>{save.isPending ? <><span className="spinner" /> Saving…</> : 'Save'}</button>
          </div>
        </form>
      </section>
    </div>
  )
}

function ClientRow({ token, client, selected, disabled, onSelect, onEdit, onLink, onChanged, onRequestDelete }: {
  token: string
  client: VlessClient
  selected: boolean
  disabled: boolean
  onSelect: () => void
  onEdit: () => void
  onLink: (result: VlessClientCreation) => void
  onChanged: () => void
  onRequestDelete: (action: ConfirmAction) => void
}) {
  const [error, setError] = useState('')
  const metrics = useQuery({
    queryKey: ['client-metrics', token, client.id],
    queryFn: () => api<ClientMetric[]>(`/api/vless/clients/${client.id}/metrics`, token),
  })
  const latest = metrics.data?.at(-1)
  const toggle = useMutation({
    mutationFn: () =>
      api<VlessClientCreation>(`/api/vless/clients/${client.id}`, token, {
        method: 'PUT',
        body: JSON.stringify({ enabled: !client.enabled }),
      }),
    onSuccess: () => { setError(''); onChanged() },
    onError: caught => setError(caught.message),
  })
  const remove = useMutation({
    mutationFn: () => api<void>(`/api/vless/clients/${client.id}`, token, { method: 'DELETE' }),
    onSuccess: () => { setError(''); onChanged() },
    onError: caught => setError(caught.message),
  })
  const copy = useMutation({
    mutationFn: () => api<VlessClientConfig>(`/api/vless/clients/${client.id}`, token),
    onSuccess: async result => {
      try {
        await navigator.clipboard.writeText(result.uri)
      } catch {
        onLink({ client, uri: result.uri })
      }
    },
    onError: caught => setError(caught.message),
  })
  const state = disabled || !client.enabled ? 'OFFLINE' : metrics.isError ? 'UNAVAILABLE' : metrics.isLoading ? 'CHECKING' : latest?.online ? 'ONLINE' : 'OFFLINE'
  const limit = clientLimit(client)

  return (
    <>
      <tr className={selected ? 'selected' : ''} onClick={onSelect}>
        <td><strong>{client.name}</strong><small className="mono">{client.id.slice(0, 18)}…</small>{limit && <small className="limit-note">{limit}</small>}</td>
        <td><span className={`peer-state ${state.toLowerCase()}`}><i />{state}</span></td>
        <td className="mono traffic">used {bytes(client.trafficUsedBytes)}{client.trafficLimitBytes ? ` / ${bytes(client.trafficLimitBytes)}` : ''}</td>
        <td className="mono">{dateTime(client.expiresAt)}</td>
        <td className="mono traffic">↓ {bytes(latest?.rxBytes ?? 0)}<br />↑ {bytes(latest?.txBytes ?? 0)}</td>
        <td className="actions">
          <button disabled={disabled} onClick={event => { event.stopPropagation(); onEdit() }}>EDIT</button>
          <button disabled={copy.isPending} onClick={event => { event.stopPropagation(); copy.mutate() }}>COPY</button>
          <button disabled={disabled || toggle.isPending} onClick={event => { event.stopPropagation(); setError(''); toggle.mutate() }}>
            {client.enabled ? 'DISABLE' : 'ENABLE'}
          </button>
          <button
            className="danger"
            disabled={disabled || remove.isPending}
            onClick={event => {
              event.stopPropagation()
              onRequestDelete({
                title: 'Delete client?',
                message: `${client.name} and its server configuration will be permanently removed. Existing vless:// link will stop working.`,
                onConfirm: () => { setError(''); remove.mutate() },
              })
            }}
          >
            DELETE
          </button>
        </td>
      </tr>
      {error && <tr><td colSpan={6} className="row-error">{error}</td></tr>}
    </>
  )
}

function ClientChart({ client, data, loading, error }: { client: VlessClient; data: ClientMetric[]; loading: boolean; error: boolean }) {
  const chart = data.map(metric => ({ ...metric, time: time(metric.timestamp) }))
  return (
    <section className="panel peer-chart">
      <PanelTitle title={`Client traffic / ${client.name}`} subtitle={`${client.enabled ? 'enabled' : 'disabled'} · cumulative Xray counters`} />
      {loading ? (
        <div className="chart-state">Loading client history…</div>
      ) : error ? (
        <div className="chart-state error-text">Client metrics could not be loaded.</div>
      ) : !chart.length ? (
        <div className="chart-state">No client metrics collected yet.</div>
      ) : (
        <div className="chart">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={chart}>
              <CartesianGrid stroke="#292c29" vertical={false} />
              <XAxis dataKey="time" tickLine={false} axisLine={false} tick={{ fill: '#777d73', fontSize: 10 }} minTickGap={35} />
              <YAxis tickLine={false} axisLine={false} tick={{ fill: '#777d73', fontSize: 10 }} tickFormatter={value => bytes(Number(value))} />
              <Tooltip contentStyle={{ background: '#181a18', border: '1px solid #3a4037', borderRadius: 4, fontFamily: 'monospace' }} formatter={value => bytes(Number(value))} />
              <Line name="RX (uplink)" dataKey="rxBytes" type="monotone" stroke="#a3e635" strokeWidth={1.8} dot={false} />
              <Line name="TX (downlink)" dataKey="txBytes" type="monotone" stroke="#60a5fa" strokeWidth={1.8} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  )
}

function ConfirmDialog({ action, onClose, onConfirm }: { action: ConfirmAction; onClose: () => void; onConfirm: () => void }) {
  return (
    <div className="overlay confirm-overlay" onMouseDown={event => event.target === event.currentTarget && onClose()}>
      <section className="dialog confirm-dialog panel">
        <div className="confirm-icon">!</div>
        <div className="eyebrow">CONFIRM / DESTRUCTIVE ACTION</div>
        <h2>{action.title}</h2>
        <p className="dialog-copy">{action.message}</p>
        <div className="dialog-actions">
          <button className="button quiet" onClick={onClose}>Cancel</button>
          <button className="button danger solid-danger" onClick={onConfirm}>Delete</button>
        </div>
      </section>
    </div>
  )
}

function LinkDialog({ link, onClose }: { link: VlessClientCreation; onClose: () => void }) {
  const [copied, setCopied] = useState(false)
  async function copy() {
    try {
      await navigator.clipboard.writeText(link.uri)
      setCopied(true)
      setTimeout(() => setCopied(false), 1800)
    } catch {
      setCopied(false)
    }
  }
  return (
    <div className="overlay">
      <section className="dialog config-dialog panel">
        <button className="close" onClick={onClose}>×</button>
        <div className="eyebrow">VLESS / URI</div>
        <h2>{link.client.name}</h2>
        <p className="dialog-copy">
          Import this link into a VLESS client. The UUID is stored encrypted and can be retrieved later; rotating it invalidates the old link.
        </p>
        <textarea className="config-output" readOnly rows={8} style={{ resize: 'none' }} value={link.uri} />
        <div className="dialog-actions">
          <button className="button quiet" onClick={() => void copy()}>{copied ? 'Copied' : 'Copy link'}</button>
          <button className="button primary" onClick={onClose}>Close</button>
        </div>
      </section>
    </div>
  )
}
