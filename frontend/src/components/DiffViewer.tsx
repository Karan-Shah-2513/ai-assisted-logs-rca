interface DiffViewerProps {
  commit: string
}

export function DiffViewer({ commit }: DiffViewerProps) {
  const lines = commit.split('\n')
  const diffStart = lines.findIndex((line) => line.startsWith('--- ') || line.startsWith('@@'))
  const heading = (diffStart >= 0 ? lines.slice(0, diffStart) : lines).filter(Boolean)
  const diff = diffStart >= 0 ? lines.slice(diffStart) : []
  const firstHeading = heading[0] || ''
  const hash = firstHeading.match(/\b[0-9a-f]{7,40}\b/i)?.[0]
  const title = firstHeading
    .replace(/^commit\s+/i, '')
    .replace(/\b[0-9a-f]{7,40}\b/i, '')
    .replace(/^[\s—–:-]+/, '')
    .trim() || 'Suspected change'

  return (
    <div className="diff-viewer">
      <div className="commit-heading">
        <span className="commit-hash">{hash || 'commit'}</span>
        <div><strong>{title}</strong><span>{heading.slice(1).join(' · ')}</span></div>
      </div>
      {diff.length > 0 ? (
        <pre className="diff-code">{diff.map((line, index) => (
          <span
            className={line.startsWith('+') ? 'diff-add' : line.startsWith('-') ? 'diff-remove' : line.startsWith('@@') ? 'diff-hunk' : ''}
            key={`${index}-${line}`}
          >{line || ' '}</span>
        ))}</pre>
      ) : <pre className="diff-code">{commit || 'No commit diff available.'}</pre>}
    </div>
  )
}
