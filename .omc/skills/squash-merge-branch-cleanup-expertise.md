---
name: squash-merge-branch-cleanup
description: git branch --merged misses squash-merged branches in this repo — cross-reference gh pr list --state merged/closed by headRefName before pruning
triggers:
  - git branch --merged
  - stale local branches
  - prune branches
  - branch cleanup
  - gh pr merge --squash
  - delete-branch
---

# Detecting merged branches in a squash-merge repo

## The Insight

This repo's entire merge workflow is `gh pr merge --squash --delete-branch` (see
[git-workflow.md](../../CLAUDE.md) and every PR merged this session). Squash-merging rewrites the
commit(s) into one new commit on `main` — the original branch-tip SHA never appears in `main`'s
ancestry. `git branch --merged origin/main` walks ancestry, so it silently misses every
squash-merged branch. In a 36-branch cleanup this session it correctly flagged only 3; the other 33
were all real, safely-merged work that `git` itself couldn't see as merged.

## Why This Matters

Trusting `git branch --merged` alone in this repo produces a false negative, not a false positive —
it under-reports what's safe to delete, not over-reports. The naive fix ("just force-delete
everything") risks losing a branch that's merely *stale* (author moved on, PR still open) rather than
actually landed. Both failure directions are bad: leaving 33 stale branches, or deleting one that was
never merged.

## Recognition Pattern

Any time you're about to prune local branches in this repo (or any squash-merge-only repo) and reach
for `git branch --merged` as the sole signal.

## The Approach

Cross-reference every local branch name against `gh pr list --state merged --json headRefName` (and
`--state closed` separately, to catch abandoned-not-merged branches like the #150 litertlm 0.14
regression branch, which must NOT be deleted-as-merged). Classify each local branch into:
1. **Safe** — has a MERGED PR head ref, or is git-confirmed via `--merged` (both signals agree).
2. **Review first** — has a CLOSED (not merged) PR; only delete after confirming it was deliberately
   abandoned (check the PR/issue for why).
3. **No PR record** — inspect manually; may be an old rebase-merged branch predating PR tracking.

```bash
locals=$(git branch --format='%(refname:short)' | grep -vw main)
merged_heads=$(gh pr list --state merged --limit 300 --json headRefName -q '.[].headRefName' | sort -u)
closed_heads=$(gh pr list --state closed --limit 200 --json headRefName -q '.[].headRefName' | sort -u)
for b in $locals; do
  grep -qx "$b" <<<"$merged_heads" && { echo "SAFE: $b"; continue; }
  grep -qx "$b" <<<"$closed_heads" && { echo "REVIEW (closed, not merged): $b"; continue; }
  echo "NO PR RECORD: $b"
done
```

Only `git branch -D` the "SAFE" bucket without further review.
