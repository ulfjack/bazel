def _deploy_jar_as_binary_impl(ctx):
    main = ctx.file.deploy_jar
    wrapper = ctx.actions.declare_file(ctx.label.name + "-wrap.sh")
    ctx.actions.write(wrapper, "#!/bin/bash\nexec java -cp " + main.path + " " + ctx.attr.main_class + " \"$@\"", is_executable=True)
    files = depset(direct = [main, wrapper])
    return [DefaultInfo(files = files, executable = wrapper)]

deploy_jar_as_binary = rule(
    implementation = _deploy_jar_as_binary_impl,
    attrs = {
      "deploy_jar": attr.label(allow_single_file = True),
      "main_class": attr.string(),
    },
)