"""Pipeline factory (plan §3.5). `pipeline/` may only import `askfirst.domain` and third-party libraries."""
import pathlib

import yaml

from askfirst.domain.models import ArtifactStore, KVCache, Pipeline
from askfirst.pipeline.stub import StubPipeline


def artifact_version(manifest: pathlib.Path, name: str) -> str:
    """`version` of one entry in `config/artifacts.yaml`; "" when the entry is still the empty G0 skeleton."""
    entries = yaml.safe_load(manifest.read_text(encoding="utf-8")) or []
    return next((e.get("version") or "" for e in entries if e.get("name") == name), "")


def build_pipeline(impl: str, config_dir: pathlib.Path, artifacts: ArtifactStore, url_cache: KVCache) -> Pipeline:
    """impl = "stub" | "real". Called ONCE at cold start; the object is reused across invocations.

    `artifacts` feeds the OCR model (AI-26, loaded lazily on the first image) and the RAG embedder + index (INT-03, loaded
    lazily on the first search; no RAG while `embedder` or `kb_index` is still empty in artifacts.yaml);
    `url_cache` (AI-24b, network URL intel) is not used yet."""
    if impl == "stub":
        return StubPipeline()
    if impl == "real":
        # The factory is the one place that builds the LLM client and the local models: the stages only see protocols.
        from askfirst.llm.bedrock import BedrockClient
        from askfirst.llm.guardrail import BedrockGuardrail
        from askfirst.local_models.embedder import E5Embedder
        from askfirst.local_models.ocr import RapidOcr
        from askfirst.pipeline.judge import load_system_prompt
        from askfirst.pipeline.real import RealPipeline
        from askfirst.pipeline.respond import KB_DIR, url_intel
        from askfirst.pipeline.retrieve import LazyRetriever, Retriever
        from askfirst.pipeline.signals import RuleEngine
        manifest = config_dir / "artifacts.yaml"
        ocr = RapidOcr(artifacts, version=artifact_version(manifest, "ocr"))
        rules = RuleEngine.from_file(KB_DIR / "rules.yaml")
        retriever = None
        if artifact_version(manifest, "embedder") and artifact_version(manifest, "kb_index"):
            embedder = E5Embedder(artifacts, version=artifact_version(manifest, "embedder"))
            retriever = LazyRetriever(lambda: Retriever.from_dir(embedder, artifacts.path("kb_index")))
        return RealPipeline(BedrockClient.from_file(config_dir / "models.yaml"), load_system_prompt(),
                            rules=rules, rules_version=rules.version, url_intel=url_intel(), ocr=ocr,
                            guard=BedrockGuardrail.from_file(config_dir / "models.yaml"), retriever=retriever)
    raise ValueError(f"unknown PIPELINE_IMPL: {impl!r}")
