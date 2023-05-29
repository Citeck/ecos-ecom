![Citeck ECOS Logo](https://raw.githubusercontent.com/Citeck/ecos-ui/develop/public/img/logo/ecos-logo.png)

# `ecos-ecom`

Welcome to the Citeck `ecos-ecom` repository! The microservice provides a solution for creating and managing email newsletters, chat bots within the platform.

## Development

To start your application in the dev profile, simply run:

    mvn spring-boot:run


### Building for production

To optimize the ecom application for production, run:

    mvn clean package

To ensure everything worked, run:

    mvn spring-boot:run -Pprod

### Testing

To launch your application's tests, run:

    mvn test

### Using docker-compose

You can fully dockerize your application and all the services that it depends on.
To achieve this, first build a docker image of your app by running:

    mvn jib:dockerBuild

Then run:

    docker-compose -f src/main/docker/app.yml up -d

## Contributing

We welcome contributions from the community to make ECOS even better. Everyone interacting in the Citeck project’s codebases, issue trackers, chat rooms, and forum is expected to follow the [contributor code of conduct](https://github.com/rubygems/rubygems/blob/master/CODE_OF_CONDUCT.md).

## Support

If you need any assistance or have any questions regarding Citeck `ecos-ecom`, please create an issue in this repository or reach out to our [support team](mailto:support@citeck.ru).

## License

Citeck `ecos-ecom` is released under the [GNU Lesser General Public License](LICENSE).
