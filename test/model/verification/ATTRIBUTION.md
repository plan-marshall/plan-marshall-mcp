# Attribution

Third-party datasets of the corpus, with their licence and holders. None of them is stored in this repository or
in a release: the import of `core.role.corpus` (`CorpusImport`, Java, `pm-mcp-core`) fetches each at the commit
pinned in its manifest `sources/<name>/manifest.json`, checks every file against its SHA-256, and generates the items
into a cache outside the repository, so that this repository holds only our part (the manifests and the overlays
`sources/<name>/overlay.json`). Every generated item names in `provenance.origin` the repository of the source, its
commit, the file, and the line of its row; the manifest names the licence as an SPDX id. Every source with a
manifest has exactly one section below, headed by its manifest `name`; its status says whether it is in use.

All sources below (Apache-2.0, MIT, CC BY 4.0) allow commercial use, change, and redistribution under the conditions stated. A dataset's
licence covers what its authors hold; content they took from elsewhere keeps its own terms, named per source.

## humanevalcomm

- Status: in use since 2026-10-20, manifest `sources/humanevalcomm/manifest.json` (HumanEvalComm v2, selected
  2026-10-14).
- Source: https://huggingface.co/datasets/jie-jw-wu/HumanEvalComm (file `HumanEvalComm_v2.csv`); paper
  "HumanEvalComm: Benchmarking the Communication Competence of Code Generation for LLMs and LLM Agent",
  Jie JW Wu and Fatemeh H. Fard, https://arxiv.org/abs/2406.00215.
- Licence: Apache-2.0, as declared on the dataset card (https://www.apache.org/licenses/LICENSE-2.0). No copyright line is
  published; the holders are the HumanEvalComm authors.
- Contains: the problems, solutions, and tests of HumanEval, MIT, Copyright (c) OpenAI
  (https://github.com/openai/human-eval).
- What our overlay adds: a sample of problems recast as verification items (the choice ask or proceed, an
  operator's reply written from the original description with the outputs it leads to as choices, an operator's
  answer in own words with its mapping); the task texts are the rows' own fields.

## input-bouncer

- Status: in use since 2026-10-20, manifest `sources/input-bouncer/manifest.json` (BouncerBench, selected
  2026-10-14).
- Source: https://huggingface.co/datasets/uw-swag/input-bouncer (file `test.csv`, stored in Git LFS); paper
  "BouncerBench", https://arxiv.org/abs/2506.17812; repository https://github.com/uw-swag/BouncerBench.
- Licence: MIT, as declared on the dataset card; holders: the BouncerBench authors.
- Contains: tasks of SWE-bench, MIT, Copyright (c) 2023 Carlos E Jimenez, John Yang, Alexander Wettig,
  Shunyu Yao, Kexin Pei, Ofir Press, Karthik R Narasimhan (https://github.com/SWE-bench/SWE-bench), and the text of GitHub issues of the SWE-bench repositories, which stays
  under the terms of its projects and authors.
- What our overlay adds: issues and labels recast as escalate-or-proceed items; a balanced sample.

## agentabstain

- Status: in use since 2026-10-20, manifest `sources/agentabstain/manifest.json` (AgentAbstain, selected
  2026-10-14).
- Source: https://huggingface.co/datasets/antiquality/agentabstain (file `tasks.jsonl`); paper "AgentAbstain: Do LLM
  Agents Know When Not to Act?", https://arxiv.org/abs/2607.10059. The companion code at
  https://github.com/AntiQuality/agentabstain (MIT, Copyright (c) 2026 The AgentAbstain Authors) is not used.
- Licence: CC BY 4.0, as declared on the dataset card (https://creativecommons.org/licenses/by/4.0/); creators:
  the AgentAbstain authors. Attribution: this section, and the source named in every generated item.
- What our overlay adds: act-or-abstain pairs that can be stated without their sandbox, recast as
  escalate-or-proceed items.

## Considered and not used

- BouncerBench Lite (`uw-swag/bouncerbench-lite`, MIT): its `test.csv` names the column `input_quality` twice,
  which the CSV reader of the import refuses, and it adds nothing the labels of `input-bouncer` need; not used.
- HiL-Bench (`ScaleAI/hil-bench`): no licence on the dataset or its repository; not used. Its design (a
  registry of blockers, each with its resolution) is taken over as an idea only.
- Ambig-SWE (`Ruan-WH/Ambig-SWE`, MIT with data): not confirmed as the authors' release; not used until it is.
