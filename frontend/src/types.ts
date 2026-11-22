export type ServerStatus = 'PENDING' | 'ONLINE' | 'ERROR'

export type Server = {
  id: string
  name: string
  host: string
  sshPort: number
  sshUser: string
  operatingSystem: string | null
  status: ServerStatus
  connectionError: string | null
  hostKeyFingerprint: string | null
  createdAt: string
}

export type VlessInbound = {
  id: string
  tag: string
  listenPort: number
  dest: string
  serverNames: string
  shortIds: string
  publicKey: string
  fingerprint: string
  flow: string
  enabled: boolean
  status: 'PENDING' | 'READY' | 'ERROR' | 'STOPPED'
  lastError: string | null
  xrayVersion: string | null
  routingReady: boolean
}

export type VlessClient = {
  id: string
  name: string
  enabled: boolean
  createdAt: string
  trafficLimitBytes: number | null
  trafficUsedBytes: number
  expiresAt: string | null
  configAvailable: boolean
  ipLimit: number | null
  allowedIpCount: number
  blockedIpCount: number
  ipLimitExceeded: boolean
}

export type VlessClientIp = {
  ip: string
  firstSeen: string
  lastSeen: string
  blocked: boolean
  blockedUntil: string | null
}

export type VlessClientCreation = { client: VlessClient; uri: string }
export type VlessClientConfig = { id: string; name: string; enabled: boolean; uri: string }

export type ServerMetric = {
  timestamp: string
  cpu: number
  ram: number
  disk: number
  netRx: number
  netTx: number
}

export type ClientMetric = {
  clientId: string
  timestamp: string
  rxBytes: number
  txBytes: number
  online: boolean
}

export type MonitorUpdate = {
  serverId: string
  status: ServerStatus
  connectionError: string | null
  server: ServerMetric | null
  clients: { clientId: string; enabled: boolean; metrics: ClientMetric | null }[]
}

export type ProvisionPayload = {
  listenPort: number
  dest: string
  serverNames: string[]
  shortIds: string[]
  fingerprint: string
  flow: string
}

export type ConfirmAction = { title: string; message: string; onConfirm: () => void }
