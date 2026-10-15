# Attribution

Third-party datasets used by the corpus, with their licence and holders. None of them is stored in this
repository or in a release; the loader (Java, `pm-mcp-core`, work package `corpus-sources`) fetches each at the commit pinned in
`sources/<source>.json`, checks it against its hashes, and generates the items into a cache, so that this repository
holds only our part (the manifests and the overlays). Every generated item names in `provenance.origin` the source, its commit, and the
row, and its licence as an SPDX id. A source is in use once its manifest lands; its status names the manifest.

All sources below (Apache-2.0, MIT, CC BY 4.0) allow commercial use, change, and redistribution under the conditions stated. A dataset's
licence covers what its authors hold; content they took from elsewhere keeps its own terms, named per source.

## HumanEvalComm

- Status: in use, pinned in `sources/humanevalcomm.json` at commit `603f72e1f8e18ecbcf910f6d988d749fd1728126`.
- Source: https://huggingface.co/datasets/jie-jw-wu/HumanEvalComm (file `HumanEvalComm_v2.csv`, SHA-256 `4db43adb9e89e4148c2b6da23b7ad18176f903fd45cdb92dd2877da3abff38d8`); paper
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

- Status: in use, pinned in `sources/bouncerbench.json` at `uw-swag/input-bouncer` commit `b1de5a2034f659fcb3953ddbbd332cd60d253697` and `uw-swag/bouncerbench-lite` commit `8be3e7e63cb790bb743a801d672eef7f9fc25063`.
- Source: https://huggingface.co/datasets/uw-swag/input-bouncer (`test.csv`, LFS object `7f03b6fa755767f5ce3040039b11a674673ce7eb8a44093d992170003e4839c1`, 33373334 bytes) and
  https://huggingface.co/datasets/uw-swag/bouncerbench-lite (`test.csv`, LFS object `86ccf4eb50237846aeee634dbac18ac4479dea6a6e90e9b9e566b48c680de85b`, 19202409 bytes); paper "BouncerBench", https://arxiv.org/abs/2506.17812;
  repository https://github.com/uw-swag/BouncerBench.
- Licence: MIT, as declared on the dataset cards; holders: the BouncerBench
  authors.
- Contains: tasks of SWE-bench, MIT, Copyright (c) 2023 Carlos E Jimenez, John Yang, Alexander Wettig,
  Shunyu Yao, Kexin Pei, Ofir Press, Karthik R Narasimhan (https://github.com/SWE-bench/SWE-bench), and the text of GitHub issues of the SWE-bench repositories, which stays
  under the terms of its projects and authors.
- What our overlay adds: issues and labels recast as escalate-or-proceed items; a balanced sample.

## AgentAbstain

- Status: in use, pinned in `sources/agentabstain.json` at commit `842228426c2a703347396501af61c7890972c7ee`.
- Source: https://huggingface.co/datasets/antiquality/agentabstain (file `tasks.jsonl`, SHA-256 `165f021e7bb8b3a1ba103cef291eb522ff219769e8e7727f1a669364a225fb63`); paper "AgentAbstain: Do LLM
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
