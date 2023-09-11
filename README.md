# nostr-relay

Nostr relay written in Java.

This is a work-in-progress project.

## Getting Started

These instructions will get you a copy of the project up and running on your local machine for development and testing purposes. See deployment for notes on how to deploy the project on a live system.

### Prerequisites

What things you need to install the software and how to install them.

```
Java JDK 11 or higher.
```

### Installing

Just clone this project on your favorite Java-Compatible IDE and have fun. 

## Available Websocket endpoint

Once started the websocket server will listen on `wss://localhost:8443` and the following endpoints are going to be available:

| URI  | Supported Methods |
| ------------- | ------------- |
| `/`  | GET  |

## Implemented NIPS

* [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md): Basic Protocol Flow
* [NIP-02](https://github.com/nostr-protocol/nips/blob/master/02.md): Contact List and Petnames
* [NIP-04](https://github.com/nostr-protocol/nips/blob/master/04.md): Encrypted Direct Message
* [NIP-09](https://github.com/nostr-protocol/nips/blob/master/09.md): Event Deletion
* [NIP-11](https://github.com/nostr-protocol/nips/blob/master/11.md): Relay Information Document
* [NIP-18](https://github.com/nostr-protocol/nips/blob/master/18.md): Reposts
* [NIP-22](https://github.com/nostr-protocol/nips/blob/master/22.md): Event `created_at` Limits
* [NIP-25](https://github.com/nostr-protocol/nips/blob/master/25.md): Reactions
* [NIP-28](https://github.com/nostr-protocol/nips/blob/master/28.md): Public Chat
* [NIP-40](https://github.com/nostr-protocol/nips/blob/master/40.md): Expiration Timeout
* [NIP-42](https://github.com/nostr-protocol/nips/blob/master/42.md): Authentication of clients to relays
* [NIP-58](https://github.com/nostr-protocol/nips/blob/master/58.md): Badges

## Deployment

```
mvn clean package
java -jar target/nostr-relay-<version>-shaded.jar
```

## Built With

* [Maven](https://maven.apache.org/) - Dependency Management

## Contributing

.

## Versioning

This project uses [SemVer](http://semver.org/) for versioning.

## Authors

* **Claudiney Nascimento** - *Initial work* - [claudineyns](https://github.com/claudineyns)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details

## Acknowledgments

The **nostr** protocol specification can be found at:
* [nostr protocol](https://github.com/nostr-protocol/nostr)
