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

# Mark the mounted repository safe for git to read.
#
# Git refuses to operate on a repository owned by a different user than the one
# running it — "detected dubious ownership in repository at '/repo/.git'" —
# which is a genuine protection against a repo dropped into a shared directory
# carrying hooks that would then run as you. Here the "other user" is only the
# host: this container runs as redpandaconsole and the checkout belongs to
# whoever cloned it.
#
# It went unnoticed because Docker Desktop on macOS presents bind-mounted files
# as owned by the asking user, so the mismatch does not exist there. On a Linux
# CI runner it does, and the console crash-looped on startup with the rest of
# the stack healthy.
#
# Written to the system config rather than passed as GIT_CONFIG_* from compose,
# because the console shells out to git and this must not depend on that child
# process inheriting the environment.
#
# Both paths, and /repo/.git is the one that actually matters. Cloning *from* a
# local path makes git check the source gitdir, so it wants the exemption on
# /repo/.git — an entry for /repo alone leaves the clone failing with the same
# message, which is how the first attempt at this fix passed a hand-run
# `git -C /repo rev-parse` and still crash-looped in CI. Git says which path it
# wants in the error itself; it is worth reading rather than assuming it means
# the worktree. /repo is kept as well so anything that reads the mount directly
# also works.
#
# Two explicit paths rather than "*", so the exemption covers exactly what needs
# it and a repository appearing anywhere else in this container is still
# refused.
USER root
RUN apk add --no-cache git
RUN git config --system --add safe.directory /repo \
 && git config --system --add safe.directory /repo/.git
USER redpandaconsole
