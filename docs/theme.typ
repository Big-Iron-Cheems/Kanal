// Kanal document theme
// Shared by all chapter files via #import "theme.typ": *

#let kanal-doc(
  title: "",
  subtitle: "",
  version: "",
  body,
) = {
  set document(title: title + " " + subtitle)
  set page(
    paper: "a4",
    margin: (x: 2.8cm, y: 3cm),
    header: context {
      if counter(page).get().first() > 1 {
        grid(
          columns: (1fr, 1fr),
          align(left, text(size: 8pt, fill: luma(130), title + " " + version)),
          align(right, text(size: 8pt, fill: luma(130), subtitle)),
        )
        line(length: 100%, stroke: 0.4pt + luma(180))
      }
    },
    footer: context {
      if counter(page).get().first() > 1 {
        line(length: 100%, stroke: 0.4pt + luma(180))
        align(center, text(size: 8pt, fill: luma(130), counter(page).display()))
      }
    },
  )
  set text(font: "New Computer Modern", size: 11pt, lang: "en")
  set par(justify: true, leading: 0.7em)
  set heading(numbering: "1.1.")
  show figure.caption: it => text(size: 9pt, fill: luma(80), style: "italic", it)

  // Code block: subtle background + smaller font, never split across pages
  show raw.where(block: true): it => block(
    width: 100%,
    inset: (x: 1em, y: 0.75em),
    radius: 4pt,
    fill: luma(246),
    stroke: 0.5pt + luma(210),
    breakable: false,
    text(size: 9.5pt, it),
  )

  // Inline code: very light highlight
  show raw.where(block: false): it => box(
    inset: (x: 2pt, y: 1pt),
    radius: 2pt,
    fill: luma(238),
    text(size: 0.92em, it),
  )

  // Links: blue + underline so they are visible
  show link: it => underline(text(fill: rgb("#2a5db0"), it))

  // Title page
  page(
    margin: (x: 3cm, y: 4cm),
    {
      v(3cm)
      align(
        center,
        {
          text(size: 36pt, weight: "bold", title)
          v(0.4em)
          text(size: 18pt, fill: luma(80), subtitle)
          v(1.2em)
          text(size: 12pt, fill: luma(100), "Version " + version)
        },
      )
      v(1fr)
      align(
        center,
        text(size: 10pt, fill: luma(130), "Kotlin-first, Java-compatible event-handler library for JDK 25"),
      )
    },
  )

  // Table of contents
  page({
    outline(
      title: "Contents",
      indent: 1.5em,
      depth: 2,
    )
  })

  body
}

// Inline code style
#let kt(code) = raw(code, lang: "kotlin")
#let java(code) = raw(code, lang: "java")

// Invariant / contract callout box
#let contract(title: none, body) = block(
  width: 100%,
  inset: (x: 1em, y: 0.8em),
  radius: 4pt,
  fill: rgb("#f0f4ff"),
  stroke: (left: 3pt + rgb("#4a6cf7")),
  {
    if title != none {
      text(weight: "bold", title)
      v(0.4em)
    }
    body
  },
)

// Warning / footgun box
#let warn(title: none, body) = block(
  width: 100%,
  inset: (x: 1em, y: 0.8em),
  radius: 4pt,
  fill: rgb("#fff8f0"),
  stroke: (left: 3pt + rgb("#e07000")),
  {
    if title != none {
      text(weight: "bold", title)
      v(0.4em)
    }
    body
  },
)

// Note / info box
#let note(title: none, body) = block(
  width: 100%,
  inset: (x: 1em, y: 0.8em),
  radius: 4pt,
  fill: rgb("#f4f4f4"),
  stroke: (left: 3pt + luma(160)),
  {
    if title != none {
      text(weight: "bold", title)
      v(0.4em)
    }
    body
  },
)

// Glossary entry: term in bold, definition follows inline
#let gterm(term, body) = pad(bottom: 0.5em, {
  text(weight: "bold", term)
  h(0.5em)
  body
})
