export function generateDefaultAvatar(username) {
  const colors = [
    '#FF6B6B',
    '#4ECDC4',
    '#45B7D1',
    '#96CEB4',
    '#FFEAA7',
    '#DDA0DD',
    '#98D8C8',
    '#F7DC6F',
    '#BB8FCE',
    '#85C1E9'
  ]

  if (!username || typeof username !== 'string') {
    username = 'User'
  }

  const hash = username.split('').reduce((acc, char) => {
    return acc + char.charCodeAt(0)
  }, 0)

  const color = colors[hash % colors.length]
  const initial = username.charAt(0).toUpperCase()

  const svg = `
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
      <rect width="100" height="100" fill="${color}"/>
      <text x="50" y="50" dy=".35em" text-anchor="middle" 
            font-size="50" font-weight="bold" fill="white" font-family="Arial, sans-serif">
        ${initial}
      </text>
    </svg>
  `

  return `data:image/svg+xml;base64,${btoa(svg)}`
}
