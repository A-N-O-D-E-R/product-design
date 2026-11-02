/**
 * Prototype généré par le LLM : HTML non fiable (D-8). Iframe sandbox avec scripts mais sans allow-same-origin
 * (origine opaque : pas d'accès au DOM parent ni aux cookies), et CSP placée avant le contenu : aucun appel réseau,
 * aucune ressource externe, aucun envoi de formulaire. Le serveur refuse en plus toute requête `Origin: null`.
 */
const CSP =
  "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; " +
  "style-src 'unsafe-inline'; img-src data:; font-src data:; form-action 'none'\">"

export function PrototypeFrame({ html, titre }: { html: string; titre: string }) {
  return (
    <iframe
      title={titre}
      sandbox="allow-scripts"
      srcDoc={CSP + html}
      className="h-[640px] w-full rounded-md border bg-white"
    />
  )
}
