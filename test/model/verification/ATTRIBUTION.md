# Attribution

Third-party material in this corpus, with its licence and its holders. Every imported item names in
`provenance.origin` the dataset, its revision, and the row it was taken from, and `license` its SPDX id.
The licence texts are in `LICENSES/`. A source is listed here before its first item lands; its status says
whether items of it are in the corpus yet.

All sources below allow commercial use, change, and redistribution under the conditions stated. A dataset's
licence covers what its authors hold; content they took from elsewhere keeps its own terms, named per source.

## HumanEvalComm

- Status: selected 2026-10-14, not imported yet (work package `question-corpus-public-datasets`).
- Source: https://huggingface.co/datasets/jie-jw-wu/HumanEvalComm (file `HumanEvalComm_v2.csv`); paper
  "HumanEvalComm: Benchmarking the Communication Competence of Code Generation for LLMs and LLM Agent",
  Jie JW Wu and Fatemeh H. Fard, https://arxiv.org/abs/2406.00215.
- Licence: Apache-2.0, as declared on the dataset card (`LICENSES/Apache-2.0.txt`). No copyright line is
  published; the holders are the HumanEvalComm authors.
- Contains: the problems, solutions, and tests of HumanEval, MIT, Copyright (c) OpenAI
  (https://github.com/openai/human-eval; `LICENSES/MIT-HumanEval.txt`).
- Changes made here (Apache-2.0, section 4b): problems are recast as verification items (a task text, the
  choice ask or proceed, an operator's reply written from the original description, the HumanEval tests as
  the grading); the selection is a sample; written own-words answers are added and marked `written`.

## BouncerBench

- Status: selected 2026-10-14, not imported yet.
- Source: https://huggingface.co/datasets/uw-swag/input-bouncer and
  https://huggingface.co/datasets/uw-swag/bouncerbench-lite; paper "BouncerBench", https://arxiv.org/abs/2506.17812;
  repository https://github.com/uw-swag/BouncerBench.
- Licence: MIT, as declared on the dataset cards (`LICENSES/MIT-BouncerBench.txt`); holders: the BouncerBench
  authors.
- Contains: tasks of SWE-bench, MIT, Copyright (c) 2023 Carlos E Jimenez, John Yang, Alexander Wettig,
  Shunyu Yao, Kexin Pei, Ofir Press, Karthik R Narasimhan (https://github.com/SWE-bench/SWE-bench;
  `LICENSES/MIT-SWE-bench.txt`), and the text of GitHub issues of the SWE-bench repositories, which stays
  under the terms of its projects and authors.
- Changes made here: issues and labels recast as escalate-or-proceed items; a balanced sample.

## AgentAbstain

- Status: selected 2026-10-14, not imported yet.
- Source: https://github.com/AntiQuality/agentabstain; paper "AgentAbstain: Do LLM Agents Know When Not to
  Act?", https://arxiv.org/abs/2607.10059.
- Licence: MIT, Copyright (c) 2026 The AgentAbstain Authors (`LICENSES/MIT-AgentAbstain.txt`).
- Changes made here: act-or-abstain pairs that can be stated without their sandbox, recast as
  escalate-or-proceed items.

## Considered and not used

- HiL-Bench (`ScaleAI/hil-bench`): no licence on the dataset or its repository; not used. Its design (a
  registry of blockers, each with its resolution) is taken over as an idea only.
- Ambig-SWE (`Ruan-WH/Ambig-SWE`, MIT with data): not confirmed as the authors' release; not used until it is.
