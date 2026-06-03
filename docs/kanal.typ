#import "theme.typ": *

#show: kanal-doc.with(
  title: "Kanal",
  subtitle: "Reference Manual",
  version: "0.2.0",
)

#set cite(style: "ieee")
#show bibliography: set heading(numbering: "1.")

#include "chapters/overview.typ"
#pagebreak()
#include "chapters/quickstart.typ"
#pagebreak()
#include "chapters/spec.typ"
#pagebreak()
#include "chapters/async.typ"
#pagebreak()
#include "chapters/design.typ"
#pagebreak()
#include "chapters/glossary.typ"
#pagebreak()
#bibliography("refs.yml", title: "References", style: "ieee")
