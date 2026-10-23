"""Pipeline factory (plan §3.5). `pipeline/` may only import `askfirst.domain` and third-party libraries."""
import pathlib

from askfirst.domain.models import ArtifactStore, KVCache, Pipeline
from askfirst.pipeline.stub import StubPipeline


def build_pipeline(impl: str, config_dir: pathlib.Path, artifacts: ArtifactStore, url_cache: KVCache) -> Pipeline:
    """impl = "stub" | "real". Called ONCE at cold start; the object is reused across invocations."""
    if impl == "stub":
        return StubPipeline()
    if impl == "real":
        raise NotImplementedError("RealPipeline: INT-01")
    raise ValueError(f"unknown PIPELINE_IMPL: {impl!r}")
