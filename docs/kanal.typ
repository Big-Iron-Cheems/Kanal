#import "theme.typ": *

#show: kanal-doc.with(
  title: "Kanal",
  subtitle: "Reference Manual",
  version: "0.3.0",
)

#set cite(style: "ieee")
#show bibliography: set heading(numbering: "1.")

// What this manual is
//
// This manual covers:
//   - formal contracts and invariants (spec, coroutines-spec)
//   - dangerous edges: non-obvious behaviour that will injure you if you don't know about it
//   - design rationale: why the implementation is what it is
//
// This manual does NOT cover:
//   - installation (see module READMEs)
//   - API walkthrough (see KDocs)
//   - usage examples (see src/examples/)

#include "chapters/spec.typ"
#pagebreak()
#include "chapters/async.typ"
#pagebreak()
#include "chapters/coroutines.typ"
#pagebreak()
#include "chapters/edges.typ"
#pagebreak()
#include "chapters/design.typ"
#pagebreak()
#include "chapters/glossary.typ"
#pagebreak()
#bibliography("refs.yml", title: "References", style: "ieee")
