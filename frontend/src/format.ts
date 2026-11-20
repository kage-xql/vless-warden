export function bytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0 B'
  if (value < 1024) return `${value} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let amount = value / 1024
  let index = 0
  while (amount >= 1024 && index < units.length - 1) {
    amount /= 1024
    index += 1
  }
  return `${amount.toFixed(amount >= 100 ? 0 : 1)} ${units[index]}`
}

export function time(value: string): string {
  return new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

export function dateTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}

export function gbToBytes(value: string): number | null {
  if (!value.trim()) return null
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) return null
  return Math.round(number * 1024 * 1024 * 1024)
}

/** Обратное преобразование с достаточной точностью, чтобы пересохранить
 * исходный лимит неизменным, когда пользователь не трогает поле. */
export function gbFromBytes(value: number | null): string {
  if (!value) return ''
  return String(Number((value / (1024 * 1024 * 1024)).toFixed(3)))
}

export function daysFromNow(value: string): string | null {
  if (!value.trim()) return null
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) return null
  return new Date(Date.now() + number * 86_400_000).toISOString()
}

export function remainingDays(expiresAt: string | null): string {
  if (!expiresAt) return ''
  const difference = new Date(expiresAt).getTime() - Date.now()
  if (difference <= 0) return 'expired'
  return `${Math.ceil(difference / 86_400_000)}d left`
}

export function clientLimit(client: { trafficLimitBytes: number | null; expiresAt: string | null }): string {
  const parts: string[] = []
  if (client.trafficLimitBytes) parts.push(`≤ ${bytes(client.trafficLimitBytes)}`)
  if (client.expiresAt) {
    const expired = new Date(client.expiresAt).getTime() <= Date.now()
    parts.push(`${new Date(client.expiresAt).toLocaleDateString()}${expired ? ' (expired)' : ''}`)
  }
  return parts.join(' · ')
}
