"""Pipeline factory (plan §3.5). `pipeline/` may only import `askfirst.domain` and third-party libraries."""
import pathlib

from askfirst.domain.models import ArtifactStore, KVCache, Pipeline
from askfirst.pipeline.stub import StubPipeline


def build_pipeline(impl: str, config_dir: pathlib.Path, artifacts: ArtifactStore, url_cache: KVCache) -> Pipeline:
    """impl = "stub" | "real". Called ONCE at cold start; the object is reused across invocations.

    `artifacts` (local models, AI-26) and `url_cache` (URL intel, AI-24a) are not used by the text-only RealPipeline yet."""
    if impl == "stub":
        return StubPipeline()
    if impl == "real":
        # The factory is the one place that builds the LLM client: the stages only see the `judge.LLM` protocol.
        from askfirst.llm.bedrock import BedrockClient
        from askfirst.pipeline.judge import load_system_prompt
        from askfirst.pipeline.real import RealPipeline
        return RealPipeline(BedrockClient.from_file(config_dir / "models.yaml"), load_system_prompt())
    raise ValueError(f"unknown PIPELINE_IMPL: {impl!r}")
