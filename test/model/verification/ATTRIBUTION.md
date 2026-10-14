# Attribution

Third-party datasets selected for the corpus, with their licence and holders. Planned use (work package
`question-corpus-public-datasets`; the loader and the manifests do not exist yet): none of them is stored in this
repository or in a release; the loader (`load.py`) is to fetch each at the commit pinned in
`sources/<source>.json`, check it against its hashes, and generate the items into a cache, so that this repository
holds only our part (the manifests and the overlays). Every generated item names in `provenance.origin` the source, its commit, and the
row, and its licence as an SPDX id. A source is listed here before its manifest lands; its status says whether
it is in use.

All sources below (Apache-2.0, MIT, CC BY 4.0) allow commercial use, change, and redistribution under the conditions stated. A dataset's
licence covers what its authors hold; content they took from elsewhere keeps its own terms, named per source.

## HumanEvalComm

- Status: selected 2026-10-14, no manifest yet (work package `question-corpus-public-datasets`).
- Source: https://huggingface.co/datasets/jie-jw-wu/HumanEvalComm (file `HumanEvalComm_v2.csv`); paper
  "HumanEvalComm: Benchmarking the Communication Competence of Code Generation for LLMs and LLM Agent",
  Jie JW Wu and Fatemeh H. Fard, https://arxiv.org/abs/2406.00215.
- Licence: Apache-2.0, as declared on the dataset card (https://www.apache.org/licenses/LICENSE-2.0). No copyright line is
  published; the holders are the HumanEvalComm authors.
- Contains: the problems, solutions, and tests of HumanEval, MIT, Copyright (c) OpenAI
  (https://github.com/openai/human-eval).
- What our overlay adds: problems are recast as verification items (a task text, the
  choice ask or proceed, an operator's reply written from the original description, the HumanEval tests as
  the grading); the selection is a sample; written own-words answers are added and marked `written`.

## BouncerBench

- Status: selected 2026-10-14, no manifest yet.
- Source: https://huggingface.co/datasets/uw-swag/input-bouncer and
  https://huggingface.co/datasets/uw-swag/bouncerbench-lite; paper "BouncerBench", https://arxiv.org/abs/2506.17812;
  repository https://github.com/uw-swag/BouncerBench.
- Licence: MIT, as declared on the dataset cards; holders: the BouncerBench
  authors.
- Contains: tasks of SWE-bench, MIT, Copyright (c) 2023 Carlos E Jimenez, John Yang, Alexander Wettig,
  Shunyu Yao, Kexin Pei, Ofir Press, Karthik R Narasimhan (https://github.com/SWE-bench/SWE-bench), and the text of GitHub issues of the SWE-bench repositories, which stays
  under the terms of its projects and authors.
- What our overlay adds: issues and labels recast as escalate-or-proceed items; a balanced sample.

## AgentAbstain

- Status: selected 2026-10-14, no manifest yet.
- Source: https://huggingface.co/datasets/antiquality/agentabstain (file `tasks.jsonl`); paper "AgentAbstain: Do LLM
  Agents Know When Not to Act?", https://arxiv.org/abs/2607.10059. The companion code at
  https://github.com/AntiQuality/agentabstain (MIT, Copyright (c) 2026 The AgentAbstain Authors) is not used.
- Licence: CC BY 4.0, as declared on the dataset card (https://creativecommons.org/licenses/by/4.0/); creators:
  the AgentAbstain authors. Attribution: this section, and the source named in every generated item.
- What our overlay adds: act-or-abstain pairs that can be stated without their sandbox, recast as
  escalate-or-proceed items.

## Considered and not used

- HiL-Bench (`ScaleAI/hil-bench`): no licence on the dataset or its repository; not used. Its design (a
  registry of blockers, each with its resolution) is taken over as an idea only.
- Ambig-SWE (`Ruan-WH/Ambig-SWE`, MIT with data): not confirmed as the authors' release; not used until it is.
