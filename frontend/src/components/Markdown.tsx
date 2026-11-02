import ReactMarkdown, { type Components } from 'react-markdown'

/** Rendu markdown (Spec, rapports, réponses du LLM). Pas de HTML brut : react-markdown l'ignore par défaut. */
const styles: Components = {
  h1: (p) => <h1 className="mt-4 mb-2 text-xl font-semibold first:mt-0" {...p} />,
  h2: (p) => <h2 className="mt-4 mb-2 text-lg font-semibold first:mt-0" {...p} />,
  h3: (p) => <h3 className="mt-3 mb-1 font-semibold" {...p} />,
  p: (p) => <p className="my-2 first:mt-0 last:mb-0" {...p} />,
  ul: (p) => <ul className="my-2 list-disc pl-6" {...p} />,
  ol: (p) => <ol className="my-2 list-decimal pl-6" {...p} />,
  li: (p) => <li className="my-0.5" {...p} />,
  a: (p) => <a className="underline" target="_blank" rel="noreferrer" {...p} />,
  code: (p) => <code className="rounded bg-muted px-1 text-[0.9em]" {...p} />,
  pre: (p) => <pre className="my-2 overflow-auto rounded bg-muted p-2" {...p} />,
  blockquote: (p) => <blockquote className="my-2 border-l-2 pl-3 text-muted-foreground" {...p} />,
}

export function Markdown({ children }: { children: string }) {
  return <ReactMarkdown components={styles}>{children}</ReactMarkdown>
}
