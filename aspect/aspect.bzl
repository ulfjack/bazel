def _javadoc_aspect_impl(target, ctx):
  if ctx.rule.kind == 'java_import':
    return struct(files = set([]))

  java_sources = [f for src in ctx.rule.attr.srcs for f in src.files]
  output = ctx.new_file(target.label.name + ".javadoc.zip")
  cmd = [output.path] + [f.path for f in java_sources]
  ctx.action(
        executable = ctx.executable._helper,
        arguments = cmd,
        inputs = java_sources,
        outputs = [output])
  return struct(output_groups = {"test" : set([output])})

# bazel build -s //src/main/java/com/google/devtools/common/options \
#    --aspects=aspect/aspect.bzl%javadoc_aspect --output_groups=test
javadoc_aspect = aspect(implementation = _javadoc_aspect_impl,
    attr_aspects = ["deps"],
    attrs = {
      "_helper" : attr.label(
          default=Label("//aspect:javadoc_helper"),
          executable = True,
          cfg = "host",
      )
    }
)

