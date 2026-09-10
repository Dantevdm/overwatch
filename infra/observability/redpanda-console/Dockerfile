# Redpanda Console, plus git.
#
# The console's only source for per-topic documentation in this version is a git
# repository — there is no filesystem provider, which was confirmed by reading
# the binary's config tags rather than inferred. It clones by shelling out to
# `git`, including for a local path, and the upstream image does not ship one:
#
#   failed to clone git repo: exec: "git": executable file not found in $PATH
#
# So this adds it. Fifteen megabytes on an Alpine base, and it buys a
# Documentation tab that explains each topic's contract to whoever opens it.
#
# The alternative was pointing the console at GitHub with a personal access
# token, which is worse in every way that matters here: credentials in compose,
# a private repository a reviewer cannot read, and documentation that stops
# working when the token expires. A read-only mount of the repository already
# present on the machine needs no secret at all.
FROM redpandadata/console:v2.7.2

USER root
RUN apk add --no-cache git
USER redpandaconsole
